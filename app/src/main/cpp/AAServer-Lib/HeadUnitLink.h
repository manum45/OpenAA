#pragma once

#include <vector>
#include <cstdint>
#include <memory>
#include <functional>
#include "Message.h"
#include "MessageType.h"
#include "enums.h"

// Forward declare SSL types
struct ssl_st;
typedef struct ssl_st SSL;
struct bio_st;
typedef struct bio_st BIO;
struct ssl_ctx_st;
typedef struct ssl_ctx_st SSL_CTX;


namespace aaserver {

class ITransport {
public:
    virtual ~ITransport() = default;
    virtual void write(const std::vector<uint8_t>& data) = 0;
};

class HeadUnitLink {
public:
    explicit HeadUnitLink(ITransport& transport);
    ~HeadUnitLink();

    void receiveData(const std::vector<uint8_t>& data);
    void sendMessage(uint8_t channel, uint8_t flags, const std::vector<uint8_t>& payload);

    std::function<void(const Message& message)> onMessageReceived;

private:
    ITransport& transport_;

    // SSL related
    void initializeSsl();
    void initializeSslContext();
    static int verifyCertificate(int preverify_ok, X509_STORE_CTX *x509_ctx);
    SSL_CTX *ctx_ = nullptr;
    SSL *ssl_ = nullptr;
    BIO *readBio_ = nullptr;
    BIO *writeBio_ = nullptr;

    // Message handling
    void processReceiveBuffer();
    void handleMessageContent(Message& message);
    std::vector<uint8_t> decryptMessage(const std::vector<uint8_t>& encryptedMsg);
    void handleVersionRequest(const Message& message);
    void sendVersionResponse(__u16 major, __u16 minor);
    void handlePingRequest(const Message& message);
    void handleSslHandshake(const Message& message);

    std::vector<uint8_t> receiveBuffer_;
};

}
