# Qwisha

<div align="center">
  <img src="https://tanzaniaprogrammers.com/qwisha/qwisha.png" alt="Qwisha Logo" width="200"/>
  
  **Modern SMS Messaging, No Internet Required**
  
  Experience WhatsApp-like features using only SMS. Edit, delete, reply, and search messages—all without an internet connection.
</div>

## 📱 About

Qwisha is an Android SMS messaging app that brings modern messaging features to traditional SMS. It operates entirely offline, requiring no internet connection or servers. Perfect for areas with unreliable internet or during network restrictions.

### The Story Behind Qwisha

Qwisha was born out of necessity during the 6-day nation-wide internet ban in October-November 2025. Like most people, the inventor experienced firsthand how frustrating it was to rely on standard SMS apps when the internet was unavailable. The limitations of traditional SMS—no message editing, no replies to specific messages, no search functionality—became painfully clear during this period of forced offline communication.

This experience sparked the idea to create a messaging app that brings modern WhatsApp-like features to SMS, ensuring that even when the internet is unavailable, people can still communicate effectively and efficiently.

The name **Qwisha** is derived from the Swahili word **"Kwisha"**, which is commonly used as slang in SMS conversations to signify the end of a conversation with mutual understanding or agreement. Just as "Kwisha" marks a meaningful conclusion to a dialogue, Qwisha aims to bring meaningful, modern messaging capabilities to SMS conversations—allowing you to communicate effectively and reach understanding, even without an internet connection.

## ✨ Features

- **✏️ Edit Messages** - Made a typo? No problem! Edit your sent messages and the recipient will see the updated version instantly.
- **🗑️ Delete Messages** - Remove messages from both your device and the recipient's device. Perfect for fixing mistakes or removing sensitive information.
- **💬 Reply to Messages** - Reply directly to specific messages, creating threaded conversations that are easy to follow.
- **🔍 Smart Search** - Search through all your messages instantly. Find any conversation, keyword, or contact with lightning-fast search.
- **📴 100% Offline** - Works entirely without internet. No servers, no data usage, just pure SMS communication.
- **✅ Delivery Reports** - Know when your messages are sent and delivered. Get real-time status updates for all your messages.

## 🚀 Getting Started

### Requirements

- Android device with SMS capability
- Android 7.0 (API level 24) or higher
- Must be set as the default SMS app to use all features

### Installation

1. Download the latest APK from [Qwisha](https://tanzaniaprogrammers.com/qwisha) page
2. Enable "Install from unknown sources" on your Android device
3. Install the APK
4. Set Qwisha as your default SMS app when prompted
5. Grant necessary permissions (SMS, Contacts, Notifications)

### Building from Source

```bash
# Clone the repository
git clone https://github.com/yourusername/qwisha.git
cd qwisha

# Open in Android Studio
# Build and run on your device or emulator
```

## 📖 How It Works

Qwisha uses a lightweight overlay protocol that extends standard SMS with enhanced features. Each message carries a compact header that encodes metadata:

```
@i=<MSGID>;c=<CMD>;r=<REFID> <CONTENT>
```

- `i` - Message ID (unique identifier)
- `c` - Command type (s=send, r=reply, e=edit, d=delete)
- `r` - Reference ID (for replies, edits, and deletes)
- `CONTENT` - Message content

### Protocol Commands

- **Send (s)**: `@i=abc123;c=s Hello World`
- **Reply (r)**: `@i=xyz789;c=r;r=abc123 Yes, I agree!`
- **Edit (e)**: `@i=edit456;c=e;r=abc123 Hello Updated World`
- **Delete (d)**: `@i=del789;c=d;r=abc123`

For detailed protocol documentation, see [Protocol Documentation](https://tanzaniaprogrammers.com/qwisha/protocol.html).

## ⚠️ Important Limitations

1. **Both Users Need Qwisha** - For advanced features like edit, delete, and reply to work, both the sender and recipient must have Qwisha installed. Regular SMS still works for non-users, but without the enhanced features.

2. **Default SMS App Required** - To fully utilize Qwisha's features, you'll need to set it as your default SMS app. This allows the app to manage your messages properly.

3. **Message Ordering** - SMS messages may arrive out of order. Qwisha handles this intelligently, but in rare cases, message ordering might be slightly affected.

4. **Standard SMS Security** - Qwisha uses standard SMS, which means messages are not end-to-end encrypted by default. Future versions will include optional encryption.

## 🔮 Future Features

- 👥 Group Messaging
- 🎨 Customization (themes, fonts, sounds)

## 🏗️ Technical Details

### Architecture

- **Language**: Kotlin
- **UI Framework**: Jetpack Compose
- **Database**: Room Database
- **Architecture**: MVVM (Model-View-ViewModel)
- **Minimum SDK**: 23 (Android 6.0)
- **Target SDK**: 34 (Android 14)

### Key Components

- `MainActivity.kt` - Main entry point
- `ConversationsScreen.kt` - Conversation list UI
- `ThreadScreen.kt` - Individual conversation thread
- `SmsReceiver.kt` - Handles incoming SMS
- `Database.kt` - Room database definitions
- `Models.kt` - Data models
- `Utils.kt` - Utility functions
- `Components.kt` - Reusable UI components

### Protocol Implementation

The app implements the Qwisha Protocol, a compact overlay protocol that extends SMS with modern messaging features. The protocol is designed to:

- Minimize overhead to avoid SMS segmentation
- Handle out-of-order delivery gracefully
- Maintain backward compatibility with regular SMS
- Support stateless message processing

## 📚 Documentation

- [Protocol Specification](https://tanzaniaprogrammers.com/qwisha/protocol.html) - Detailed technical documentation
- [Website](https://tanzaniaprogrammers.com/qwisha/index.html) - Project website and documentation

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

1. Fork the repository
2. Create your feature branch (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 👤 Author

**Vicent Laizer**

- Website: [TanzaniaProgrammers.com](https://tanzaniaprogrammers.com/qwisha)
- GitHub: [@yourusername](https://github.com/vincent-laizer)

## 🙏 Acknowledgments

- Inspired by the need for reliable offline messaging during internet restrictions
- Built with modern Android development best practices
- Uses open-source libraries and frameworks

## 📊 Project Status

🚧 **Active Development** - The project is currently in active development. Features may change, and there may be bugs. Use at your own risk.

## 💬 Support

For support, please open an issue on the GitHub repository or visit the [project website](website/index.html).

---

<div align="center">
  Made with ❤️ by Tanzanians
  
  **Modern messaging, resilient to internet restrictions**
</div>
