# Filament Manager

**Filament Manager** is an open-source Android application that currently supports only the Bambu Lab ecosystem. It helps users manage their 3D printing filament inventory, monitor printer status, and synchronize data directly with Bambu Lab AMS units.

## Screenshots
<p align="center">
  <img src="Screenshots/Screenshot_20260418_195247_Filament Manager.jpg" width="30%" />
  <img src="Screenshots/Screenshot_20260418_195302_Filament Manager.jpg" width="30%" />
  <img src="Screenshots/Screenshot_20260418_195523_Filament Manager.jpg" width="30%" />
  <br>
  <img src="Screenshots/Screenshot_20260418_195549_Filament Manager.jpg" width="30%" />
  <img src="Screenshots/Screenshot_20260418_210133_Filament Manager.jpg" width="30%" />
</p>

## About This Project
This is my very first Android development project. To bring this idea to life, I utilized **AI assistance** to help write, debug, and document the code. The goal was to create a functional, modern tool for the 3D printing community while learning the ropes of Android development.

## Core Features
- **AMS & Printer Sync:** Integrates with Bambu Lab's MQTT broker to show real-time tray information and printer status.
- **Inventory Management:** Track material weight, color, and type with automated usage monitoring.
- **NFC Spool Scanning:** Use your phone's NFC hardware to identify and log official Bambu Lab RFID tags.
- **Web Scraper:** Built-in Jsoup scraper for cataloging official Bambu Lab filament listings and availability.
- **Availability Notifications:** Automatically tracks when out-of-stock filaments become available again on the official store and sends background notifications.
- **Local Security:** Uses hardware-backed encryption to protect sensitive data like the Bambu account token and UID locally on your device. Your full account credentials (passwords) are not stored by the app.

## Tech Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose (100%)
- **Database:** Room (with Paging 3 for performance)
- **Background Tasks:** WorkManager
- **Networking:** HiveMQ MQTT Client, Jsoup, Volley

## Development Context
As my first app, this project focuses on clean architecture using modern Android practices like Hilt for Dependency Injection and Coroutines for asynchronous work. The AI integration allowed for robust error handling and documentation of complex components like the MQTT manager and the web scraper logic.

## Disclaimer
This project is an independent community effort and is **not** affiliated with, associated with, or endorsed by Bambu Lab.

## License
This project is open-source. See the LICENSE file for details.
