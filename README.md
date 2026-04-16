# MindFrame 🤖

MindFrame is a powerful Android-based AI assistant designed to navigate and control your device using natural language. It combines the reasoning capabilities of Large Language Models (LLMs) via Ollama with Android's Accessibility Services to "see" and "interact" with the mobile UI.

## 🚀 Features

- **Reasoning-Action-Verification Loop**: The agent observes the current screen, thinks about the next step, executes an action (click, type, scroll), and verifies the result before proceeding.
- **Self-Learning Skills**: Successful task execution logs are automatically synthesized into reusable `SKILL.md` recipes, allowing the agent to learn and repeat complex workflows.
- **Privacy-First Design**: Includes a built-in privacy system that automatically blinds the agent when sensitive financial or banking applications are in focus.
- **Ollama Integration**: Completely local-first or cloud-connected LLM support via the Ollama API (supports models like Llama 3, Phi-3, and Mistral).
- **Floating Overlay**: A premium chat interface that floats over other apps, providing real-time status updates (Thinking, Acting, Verifying).

## 🛠️ Architecture

- **`AgentOrchestrator`**: The central brain managing the task lifecycle.
- **`AccessibilityDriver`**: The eyes and hands, capturing a compacted UI tree and performing gestures.
- **`SkillRegistry`**: A collection of low-level tools (Search Web, Set Alarm, Type, Click) and high-level recipes.
- **`OverlayService`**: The foreground service managing the floating UI bubble.

## 📋 Prerequisites

- **Android 14+** (SDK 34+)
- **Ollama Server**: Running locally or accessible via a URL.
- **Accessibility Permissions**: Required for screen observation and interaction.
- **Overlay Permissions**: Required for the floating UI.

## ⚙️ Setup

1. **Clone the repository.**
2. **Build and install** the APK using Android Studio.
3. **Grant Permissions**: The app will guide you through granting Accessibility, Overlay, and Battery Optimization permissions on first launch.
4. **Configure LLM**:
   - Open **Settings** from the floating overlay.
   - Enter your **Ollama Base URL** and **Model Name** (e.g., `llama3`).
   - (Optional) Enter your API Key if using a cloud-hosted Ollama instance.

## 🧪 Usage Examples

- *"Tell me the weather in Tokyo."* (Web Search -> Summary)
- *"Set an alarm for 7:30 AM labeled 'Gym'."* (Direct System Action)
- *"Open WhatsApp and send 'Hello' to John."* (Multi-step UI Interaction)

## 🛡️ Privacy & Security

- **Encrypted Storage**: API keys and sensitive settings are stored using `EncryptedSharedPreferences`.
- **Blacklist**: Banking apps are hardcoded to be invisible to the agent to ensure financial data never leaves the device.

---
*Created by [abishaziz](https://github.com/abishaziz) with ❤️ and Antigravity AI.*
