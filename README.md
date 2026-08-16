This is a proof of concept for an app communicating with an Android Auto headunit.


Much of it is based on AACS:
https://github.com/tomasz-grobelny/AACS



# Design

## Communication
- UsbHandler receives the intents on USB device connection, instantiates an AccessoryCommunicator
- AccessoryCommunicator instantiates a UsbStreamer for reading/writing to/from USB, and a MessageHandler that decodes the messages
- MessageHandler instantiates an SslHandler and uses it for encryption/decryption



# License

GPLv3
