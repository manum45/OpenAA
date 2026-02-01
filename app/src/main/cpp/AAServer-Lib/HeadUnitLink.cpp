#include "HeadUnitLink.h"
#include "utils.h"
#include "PingRequest.pb.h"
#include "PingResponse.pb.h"
#include <openssl/ssl.h>
#include <openssl/err.h>
#include <stdexcept>

#define CRT_FILE "android_auto.crt"
#define PRIVKEY_FILE "android_auto.key"
#define DHPARAM_FILE "dhparam.pem"

namespace aaserver {

HeadUnitLink::HeadUnitLink(ITransport& transport) : transport_(transport) {
    initializeSslContext();
}

HeadUnitLink::~HeadUnitLink() {
    if (ssl_) {
        SSL_free(ssl_);
    }
    if (ctx_) {
        SSL_CTX_free(ctx_);
    }
}

void HeadUnitLink::receiveData(const std::vector<uint8_t>& data) {
    receiveBuffer_.insert(receiveBuffer_.end(), data.begin(), data.end());
    processReceiveBuffer();
}

void HeadUnitLink::processReceiveBuffer() {
    while (receiveBuffer_.size() >= 4) {
        uint16_t length = (receiveBuffer_[2] << 8) | receiveBuffer_[3];
        if (receiveBuffer_.size() >= 4 + length) {
            Message message;
            message.channel = receiveBuffer_[0];
            message.flags = receiveBuffer_[1];
            
            std::vector<uint8_t> payload(receiveBuffer_.begin() + 4, receiveBuffer_.begin() + 4 + length);
            message.content = (message.flags & 0x8) ? decryptMessage(payload) : payload;

            handleMessageContent(message);

            receiveBuffer_.erase(receiveBuffer_.begin(), receiveBuffer_.begin() + 4 + length);
        } else {
            break;
        }
    }
}

void HeadUnitLink::handleMessageContent(Message& message) {
    const __u16 *shortView = (const __u16 *)message.content.data();
    MessageType messageType = (MessageType)be16_to_cpu(shortView[0]);

    switch (messageType) {
        case MessageType::VersionRequest:
            handleVersionRequest(message);
            break;
        case MessageType::SslHandshake:
            handleSslHandshake(message);
            break;
        case MessageType::PingRequest:
            handlePingRequest(message);
            break;
        default:
            if (onMessageReceived) {
                onMessageReceived(message);
            }
            break;
    }
}

std::vector<uint8_t> HeadUnitLink::decryptMessage(const std::vector<uint8_t>& encryptedMsg) {
    ERR_clear_error();

    auto bytesWritten = BIO_write(readBio_, encryptedMsg.data(), encryptedMsg.size());
    if (bytesWritten < 0) {
        throw std::runtime_error("BIO_write failed");
    }
    const int plainBufSize = 100 * 1024;
    char plainBuf[plainBufSize];
    auto ret = SSL_read(ssl_, plainBuf, plainBufSize);
    if (ret < 0) {
        auto err = SSL_get_error(ssl_, ret);
        throw std::runtime_error("SSL_read failed");
    }
    return std::vector<uint8_t>(plainBuf, plainBuf + ret);
}

void HeadUnitLink::handleVersionRequest(const Message& message) {
    const __u16 *shortView = (const __u16 *)(message.content.data() + 2);
    auto versionMajor = be16_to_cpu(shortView[0]);
    auto versionMinor = be16_to_cpu(shortView[1]);
    if (versionMajor == 1)
        sendVersionResponse(1, 5);
    else
        throw std::runtime_error("unsupported version");
}

void HeadUnitLink::sendVersionResponse(__u16 major, __u16 minor) {
    std::vector<uint8_t> msg;
    pushBackInt16(msg, MessageType::VersionResponse);
    pushBackInt16(msg, major);
    pushBackInt16(msg, minor);
    pushBackInt16(msg, 0); // 0 => version match
    sendMessage(0, EncryptionType::Plain | FrameType::Bulk, msg);
}

void HeadUnitLink::handlePingRequest(const Message& message) {
    tag::aas::PingRequest preq;
    preq.ParseFromArray(message.content.data() + 2, message.content.size() - 2);

    tag::aas::PingResponse presp;
    presp.set_timestamp(preq.timestamp());
    std::vector<uint8_t> plainMsg;
    pushBackInt16(plainMsg, MessageType::PingResponse);
    std::string serialized_resp = presp.SerializeAsString();
    plainMsg.insert(plainMsg.end(), serialized_resp.begin(), serialized_resp.end());

    sendMessage(0, EncryptionType::Encrypted | FrameType::Bulk, plainMsg);
}

void HeadUnitLink::initializeSslContext() {
    SSL_load_error_strings();
    OpenSSL_add_ssl_algorithms();
    const SSL_METHOD *method = SSLv23_server_method();

    ctx_ = SSL_CTX_new(method);
    if (!ctx_) {
        throw std::runtime_error("Error while creating SSL context");
    }

    SSL_CTX_set_ecdh_auto(ctx_, 1);
    if (SSL_CTX_use_certificate_file(ctx_, CRT_FILE, SSL_FILETYPE_PEM) <= 0) {
        throw std::runtime_error("Error on SSL_CTX_use_certificate_file");
    }

    if (SSL_CTX_use_PrivateKey_file(ctx_, PRIVKEY_FILE, SSL_FILETYPE_PEM) <= 0) {
        throw std::runtime_error("Error on SSL_CTX_use_PrivateKey_file");
    }

    DH *dh_2048 = NULL;
    FILE *paramfile = fopen(DHPARAM_FILE, "r");
    if (paramfile) {
        dh_2048 = PEM_read_DHparams(paramfile, NULL, NULL, NULL);
        fclose(paramfile);
    } else {
        throw std::runtime_error("Cannot read DH parameters file");
    }
    if (dh_2048 == NULL) {
        throw std::runtime_error("Reading DH parameters failed");
    }
    if (SSL_CTX_set_tmp_dh(ctx_, dh_2048) != 1) {
        throw std::runtime_error("SSL_CTX_set_tmp_dh failed");
    }
    SSL_CTX_set_verify(ctx_, SSL_VERIFY_PEER, &HeadUnitLink::verifyCertificate);
    SSL_CTX_set_options(ctx_, SSL_OP_NO_TLSv1_3);
}

int HeadUnitLink::verifyCertificate(int preverify_ok, X509_STORE_CTX *x509_ctx) {
    return 1;
}

void HeadUnitLink::initializeSsl() {
    if (ssl_)
        return;
    ssl_ = SSL_new(ctx_);
    readBio_ = BIO_new(BIO_s_mem());
    writeBio_ = BIO_new(BIO_s_mem());
    SSL_set_accept_state(ssl_);
    SSL_set_bio(ssl_, readBio_, writeBio_);
}

void HeadUnitLink::handleSslHandshake(const Message& message) {
    initializeSsl();
    BIO_write(readBio_, message.content.data() + 2, message.content.size() - 2);

    auto ret = SSL_accept(ssl_);
    if (ret == -1) {
        auto error = SSL_get_error(ssl_, ret);
        if (error != SSL_ERROR_WANT_READ)
            throw std::runtime_error("SSL_accept failed");
    }

    std::vector<uint8_t> msg;
    pushBackInt16(msg, MessageType::SslHandshake);
    auto bufferSize = 512;
    char buffer[bufferSize];
    int len;
    while ((len = BIO_read(writeBio_, buffer, bufferSize)) > 0) {
        std::copy(buffer, buffer + len, std::back_inserter(msg));
    }
    
    sendMessage(0, EncryptionType::Plain | FrameType::Bulk, msg);
}

void HeadUnitLink::sendMessage(uint8_t channel, uint8_t flags, const std::vector<uint8_t>& payload) {
    std::vector<uint8_t> final_packet;
    std::vector<uint8_t> data_to_send;

    if (flags & 0x8) { // Encrypted
        if (!ssl_) {
            throw std::runtime_error("SSL not initialized");
        }
        auto ret = SSL_write(ssl_, payload.data(), payload.size());
        if (ret < 0) {
            throw std::runtime_error("SSL_write error");
        }
        
        int len = 0;
        int buf_size = payload.size() + 256; // Some extra space for encryption overhead
        std::vector<uint8_t> encrypted_buf(buf_size);
        len = BIO_read(writeBio_, encrypted_buf.data(), buf_size);
        if (len < 0) {
            throw std::runtime_error("BIO_read error");
        }
        data_to_send.assign(encrypted_buf.begin(), encrypted_buf.begin() + len);
    } else { // Plain
        data_to_send = payload;
    }

    final_packet.push_back(channel);
    final_packet.push_back(flags);
    pushBackInt16(final_packet, data_to_send.size());
    final_packet.insert(final_packet.end(), data_to_send.begin(), data_to_send.end());

    transport_.write(final_packet);
}

}
