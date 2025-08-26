# GhostNet: Anonymous Peer-to-Peer Chat

**GhostNet** is a secure, anonymous, and decentralized chat application for Android that allows you to communicate with nearby users without needing an internet connection. It creates a resilient mesh network using Bluetooth and Wi-Fi Direct, enabling messages to hop between devices to extend their range far beyond standard Bluetooth capabilities.


## Key Features

* **Completely Offline:** Works without any internet or cellular connection. Perfect for remote areas, crowded events, or situations where connectivity is unavailable.
* **True Anonymity:** No accounts, no phone numbers, no personal information required. Users can set a temporary name for each session.
* **Decentralized Mesh Network:** Devices connect directly to each other. There are no central servers, making the network resilient and censorship-resistant.
* **Secure:** The connection between devices is encrypted by the Nearby Connections API, and no message history is stored on the device after the session ends.

## Working On
* **Multi-Hop Messaging:** Messages automatically hop from device to device, dramatically extending the communication range beyond a single Bluetooth connection.
* **Real-Time Communication:** Features a dynamic group chat with a typing indicator to show when other users are active.

## How It Works

GhostNet is built on top of Google's **Nearby Connections API**, which abstracts the complexity of Bluetooth and Wi-Fi Direct into a simple, high-level API.

The app uses the `P2P_CLUSTER` strategy, which creates a many-to-many network topology. When the app is launched, it automatically begins both **advertising** (making itself visible) and **discovering** (looking for other devices).

When a device discovers a new peer, it automatically requests a connection. Once connected, they form a small part of the mesh. When a user sends a message, it is broadcast to all directly connected peers. Each peer that receives the message checks if it has seen the message before (using a unique message ID to prevent infinite loops) and, if not, forwards it to all of its own peers. This forwarding mechanism is what enables the multi-hop capability.

## Getting Started & How to Test

To test the multi-hop mesh functionality, you need at least two Android devices (physical devices or emulators).

1.  **Clone the Repository:**
    ```
    git clone [https://github.com/arp89/Ghostnet.git](https://github.com/arp89/Ghostnet.git)
    ```
2.  **Open in Android Studio:** Open the cloned project in Android Studio (Narwhal 2025.1.1.14 or newer).
3.  **Run the App:**
    * Launch two or more emulators via **Tools > Device Manager**.
    * Run the app on each emulator.
4.  **Onboarding:**
    * On each device, proceed through the introductory pages.
    * On the final page, enter a unique temporary name for each user and tap "Enter GhostNet".
    * Accept the necessary Bluetooth and location permissions.
5.  **Connect & Chat:**
    * The devices will automatically start discovering and connecting to each other.
    * Once connected, the app will transition to the group chat screen.
    * Send a message from one device, and it will appear on all other devices in the mesh.

## Technology Stack

* **Language:** [Kotlin](https://kotlinlang.org/)
* **UI Toolkit:** [Jetpack Compose](https://developer.android.com/jetpack/compose)
* **Connectivity:** [Google Nearby Connections API](https://developers.google.com/nearby/connections/overview)
