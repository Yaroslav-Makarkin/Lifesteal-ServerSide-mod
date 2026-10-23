# LifeSteal rewrite

## Project
Fabric mod for Minecraft 26.3. Server-side rewrite of LifeSteal (Java, Gradle).
Vanilla clients must be able to join with no mod and no required resource pack.

## Hard limits
- Server-side only. No client entrypoint, no client mixins, no custom registered items/blocks/entities.
- A "custom item" is a vanilla item with changed data components (custom_data, custom_name, lore, enchantments). Never use the legacy NBT API.
- Menus are vanilla chest-style container GUIs driven entirely by the server.

## Rules
1. All code comments are in Czech. Identifiers are in English.
2. Read files one at a time, never all at once.
3. Do only the current phase. No extra features, refactors or dependencies that were not asked for.
4. After every phase run .\gradlew.bat build, then start the dev server with .\gradlew.bat runServer and confirm the mod loads without errors. Report the result before continuing.
5. Ask before deleting files or changing the project structure.
6. Make one change at a time. If the build fails, diagnose and fix it yourself; do not paste whole stack traces back.
7. If you notice you are repeating the same thought or the same attempt, STOP immediately and tell me what you tried and what failed. Maximum 3 attempts for any single problem; after that keep the current working code and ask me.

## Versions and API
Minecraft 26.3 is newer than your training data. Never guess an API from memory.
Before using any Minecraft or Fabric class or method, verify its exact signature in the project sources (genSources task) or on docs.fabricmc.net. If you cannot verify it, say so instead of guessing.
Do not change Gradle plugin IDs or config you do not recognise (for example fabric-loom-remap) until you have searched the exact string.
Java: Minecraft 26.3 needs Java 25+. Compile with options.release = 25.