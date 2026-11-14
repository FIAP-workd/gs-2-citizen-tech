# Chave Segura — Android

Kotlin + Jetpack Compose. Pacotes em `app/src/main/java/com/chavesegura/`:
`ui`, `domain`, `data`, `security`, `vault`, `pairing`, `communication`, `authorization`.

Requisitos: JDK 17 e Android SDK 35 (Android Studio já inclui ambos).

```bash
gradle wrapper --gradle-version 8.10.2   # uma vez, para gerar ./gradlew (versionar o resultado)
./gradlew assembleDebug testDebugUnitTest
```

> O Gradle wrapper ainda não está versionado. Ao abrir o projeto no Android Studio,
> gere-o e faça commit de `gradlew`, `gradlew.bat` e `gradle/wrapper/`. O CI usa
> o wrapper automaticamente quando ele existir.
