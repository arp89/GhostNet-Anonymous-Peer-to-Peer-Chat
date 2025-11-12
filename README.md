# GhostNet: Anonymous Peer-to-Peer Chat

GhostNet is a secure, anonymous, and decentralized chat application for Android that allows users to communicate without internet or network coverage.
It creates a resilient mesh network using Bluetooth and Wi-Fi Direct, enabling messages and files to hop between devices, extending the range far beyond standard Bluetooth limits.


## Key Features

**Completely Offline:** Works without any internet or cellular connection — ideal for remote areas, crowded events, or emergency communication.
**True Anonymity:** No accounts, phone numbers, or personal data required. Each session uses a temporary username.
**Web-of-Trust Model:** Connection requests must be approved by a host before joining, preventing unauthorized access.
**Decentralized Mesh Network:** No servers, no cloud — devices connect directly to each other, forming a self-healing, censorship-resistant network.
**End-to-End Encryption:**Messages encrypted with AES-256 (symmetric).
Messages signed and verified with RSA-2048 (asymmetric).
Each message is uniquely signed and verified for authenticity.
**File & Attachment Sharing: **Send images or documents securely between peers.
**Modern UI:** Built entirely with Jetpack Compose, featuring dark-cyan cyber aesthetics.

## Working On

**Multi-Hop File Transfer:** Extend mesh relaying beyond messages to include files and attachments
**Delivery Receipts & Progress Indicators:** Real-time file transfer feedback for both sender and receiver.
**Whisper Mode:** Temporary “auto-delete” messages for enhanced privacy.

## How It Works

GhostNet uses Google’s Nearby Connections API with the P2P_CLUSTER strategy, which enables many-to-many peer connections over Bluetooth, BLE, and Wi-Fi Direct.
**Discovery & Advertising:** When launched, each device advertises its presence and scans for others using a shared channel password hash.
**Web-of-Trust Connection:** A joining user must enter the same password. The host receives an approval prompt and can approve or reject the connection.
Mesh Formation:
Once connected, peers form part of the mesh network.
When a message or file is sent, it’s:
Encrypted (AES-256)
Digitally signed (RSA-2048)
Broadcast to all directly connected peers
Each peer checks if the message is new, then forwards it — enabling multi-hop propagation.

## Getting Started & How to Test
Open the cloned folder in Android Studio (Giraffe / Hedgehog / Iguana / Narwhal 2025.1.1+).
**Run the App**
Use two or more physical devices or emulators.
Go to Build → Build APK(s) if you want a .apk file.
Each device must have Bluetooth and Location turned ON.
**Onboarding**
Launch the app and follow the introduction screens.
Enter a temporary username and a shared password.
**Choose:**
Host: Creates a secure channel.
Join: Searches for the host and requests approval.
The host approves the request → chat begins!

## Technology Stack

| Layer            | Technology                                  |
| ---------------- | ------------------------------------------- |
| **Language**     | Kotlin                                      |
| **UI**           | Jetpack Compose                             |
| **Connectivity** | Google Nearby Connections API               |
| **Encryption**   | AES-256 (CBC) + RSA-2048 (SHA-256 with RSA) |
| **Architecture** | MVVM-style single-activity Compose app      |
| **Build Tools**  | Gradle + Android Studio                     |

## Security Design
**AES-256-CBC:** Used for message encryption (32-byte key from SHA-256 hash of channel password).
**RSA-2048:** Used for message signing and verification.
**SHA-256 Hashing:** Used for secure channel matching (shared password hash).
**No Data Retention:** Messages and files are stored only temporarily in memory/cache.
**Device-Level Isolation:** Each device generates its own RSA key pair on every session.
