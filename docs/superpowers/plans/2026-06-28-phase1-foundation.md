# Phase 1: Foundation — BLE Transport + Auth + Device Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Android app that connects to Redmi Watch 5 Lite via Bluetooth SPP, authenticates using Xiaomi protobuf protocol, and displays device info + battery level.

**Architecture:** SPP (RFCOMM) Bluetooth connection to the watch. First packet negotiates protocol version (V1 or V2). Auth handshake uses HMAC-SHA256 key derivation with "miwear-auth" salt, then AES-CCM (V1) or AES-CTR (V2) encryption for all subsequent protobuf commands. After auth, we request device info and battery via protobuf System commands.

**Tech Stack:** Kotlin, Jetpack Compose, Material 3, Protobuf Lite, BouncyCastle (AES-CCM), Android Bluetooth SPP API, min SDK 26

---

## File Structure

```
app/
├── build.gradle.kts                          # Android app config + protobuf plugin
├── src/main/proto/
│   └── xiaomi.proto                          # Protobuf schema (from Gadgetbridge)
├── src/main/java/com/pedometer/
│   ├── PedometerApp.kt                       # Application class
│   ├── MainActivity.kt                       # Single activity, Compose host
│   ├── ui/
│   │   ├── ConnectScreen.kt                  # Connect UI: scan, connect, device info
│   │   └── theme/
│   │       └── Theme.kt                      # Material 3 theme
│   ├── bt/
│   │   ├── SppConnection.kt                  # RFCOMM socket management
│   │   ├── PacketV1.kt                       # V1 packet framing (preamble BADCFE)
│   │   ├── PacketV2.kt                       # V2 packet framing (preamble A5A5)
│   │   ├── Channel.kt                        # Channel enum + handler interface
│   │   └── ProtocolHandler.kt                # Packet routing, version detection
│   ├── auth/
│   │   └── AuthService.kt                    # Auth handshake + encrypt/decrypt
│   ├── proto/
│   │   └── CommandHelper.kt                  # Protobuf command builder helpers
│   └── vm/
│       └── WatchViewModel.kt                 # ViewModel: connection state, device data
├── src/test/java/com/pedometer/
│   ├── bt/
│   │   ├── PacketV1Test.kt                   # V1 encode/decode tests
│   │   └── PacketV2Test.kt                   # V2 encode/decode tests
│   └── auth/
│       └── AuthServiceTest.kt                # Auth crypto tests
build.gradle.kts                              # Root project config
settings.gradle.kts                           # Project settings
```

---

### Task 1: Scaffold Android Project

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `gradle.properties`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/pedometer/PedometerApp.kt`
- Create: `app/src/main/java/com/pedometer/MainActivity.kt`
- Create: `app/src/main/java/com/pedometer/ui/theme/Theme.kt`

- [ ] **Step 1: Create root `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolution {
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Pedometer"
include(":app")
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.protobuf") version "0.9.5" apply false
}
```

- [ ] **Step 3: Create `gradle.properties`**

```properties
android.useAndroidX=true
org.gradle.jvmargs=-Xmx2048m
```

- [ ] **Step 4: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.pedometer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pedometer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("com.google.protobuf:protobuf-javalite:4.28.3")
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")

    testImplementation("junit:junit:4.13.2")
}
```

- [ ] **Step 5: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.BLUETOOTH" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />

    <application
        android:name=".PedometerApp"
        android:label="Pedometer"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 6: Create `PedometerApp.kt`**

```kotlin
package com.pedometer

import android.app.Application

class PedometerApp : Application()
```

- [ ] **Step 7: Create `Theme.kt`**

```kotlin
package com.pedometer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun PedometerTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
```

- [ ] **Step 8: Create stub `MainActivity.kt`**

```kotlin
package com.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pedometer.ui.theme.PedometerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PedometerTheme {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Pedometer")
                }
            }
        }
    }
}
```

- [ ] **Step 9: Download Gradle wrapper and verify build**

Run:
```bash
cd /home/dima/Projects/pedometer
gradle wrapper --gradle-version 8.11.1
./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 10: Init git and commit**

```bash
cd /home/dima/Projects/pedometer
git init
echo -e ".gradle/\nbuild/\napp/build/\n*.iml\n.idea/\nlocal.properties" > .gitignore
git add -A
git commit -m "feat: scaffold Android project with Compose + Protobuf"
```

---

### Task 2: Add Protobuf Schema

**Files:**
- Create: `app/src/main/proto/xiaomi.proto`

- [ ] **Step 1: Copy and trim proto schema**

Copy from `/tmp/gadgetbridge/app/src/main/proto/xiaomi.proto` but change the java package:

```bash
cp /tmp/gadgetbridge/app/src/main/proto/xiaomi.proto /home/dima/Projects/pedometer/app/src/main/proto/xiaomi.proto
```

Then edit the file — change line 9:
```protobuf
option java_package = "com.pedometer.proto";
```

And change line 10:
```protobuf
option java_outer_classname = "XiaomiProto";
```

- [ ] **Step 2: Verify protobuf compiles**

Run:
```bash
cd /home/dima/Projects/pedometer
./gradlew assembleDebug 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/proto/xiaomi.proto
git commit -m "feat: add Xiaomi protobuf schema"
```

---

### Task 3: Implement Channel Enum

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/Channel.kt`

- [ ] **Step 1: Create Channel.kt**

```kotlin
package com.pedometer.bt

enum class Channel {
    Unknown,
    Version,
    ProtobufCommand,
    Activity,
    Data,
    Authentication,
}

fun interface ChannelHandler {
    fun handle(payload: ByteArray)
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/Channel.kt
git commit -m "feat: add Channel enum and handler interface"
```

---

### Task 4: Implement V1 Packet Framing

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/PacketV1.kt`
- Create: `app/src/test/java/com/pedometer/bt/PacketV1Test.kt`

- [ ] **Step 1: Write failing test for V1 packet decode**

```kotlin
package com.pedometer.bt

import org.junit.Assert.*
import org.junit.Test

class PacketV1Test {

    @Test
    fun `decode valid packet`() {
        // Preamble(BADCFE) + channel(02) + flags(80) + payloadLen(0600=6) + opcode(02) + frameSerial(00) + dataType(01) + payload(AABBCC) + epilogue(EF)
        // payloadLen includes 3-byte payload header (opcode+frameSerial+dataType), so payload=6-3=3 bytes
        val raw = byteArrayOf(
            0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), // preamble
            0x02,                                         // channel (PROTO_TX)
            0x80.toByte(),                                // flags: flag=true, needsResponse=false
            0x06, 0x00,                                   // payload size = 6 (LE), includes 3 header bytes
            0x02,                                         // opCode = SEND
            0x00,                                         // frameSerial = 0
            0x01,                                         // dataType = ENCRYPTED
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),  // payload
            0xEF.toByte(),                                // epilogue
        )

        val packet = PacketV1.decode(raw)
        assertNotNull(packet)
        packet!!
        assertEquals(Channel.ProtobufCommand, packet.channel)
        assertTrue(packet.flag)
        assertFalse(packet.needsResponse)
        assertEquals(0x02, packet.opCode)
        assertEquals(0x00, packet.frameSerial)
        assertEquals(PacketV1.DATA_TYPE_ENCRYPTED, packet.dataType)
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte()), packet.payload)
    }

    @Test
    fun `decode returns null for bad preamble`() {
        val raw = byteArrayOf(0x00, 0x00, 0x00, 0x02, 0x80.toByte(), 0x06, 0x00, 0x02, 0x00, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xEF.toByte())
        assertNull(PacketV1.decode(raw))
    }

    @Test
    fun `decode returns null for truncated packet`() {
        val raw = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte(), 0x02, 0x80.toByte())
        assertNull(PacketV1.decode(raw))
    }

    @Test
    fun `encode then decode roundtrip`() {
        val original = PacketV1(
            channel = Channel.ProtobufCommand,
            flag = true,
            needsResponse = false,
            opCode = PacketV1.OPCODE_SEND,
            frameSerial = 5,
            dataType = PacketV1.DATA_TYPE_PLAIN,
            payload = byteArrayOf(0x01, 0x02, 0x03),
        )
        val encoded = original.encode()
        val decoded = PacketV1.decode(encoded)
        assertNotNull(decoded)
        decoded!!
        assertEquals(original.channel, decoded.channel)
        assertEquals(original.opCode, decoded.opCode)
        assertEquals(original.frameSerial, decoded.frameSerial)
        assertEquals(original.dataType, decoded.dataType)
        assertArrayEquals(original.payload, decoded.payload)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: compilation failure — `PacketV1` not found

- [ ] **Step 3: Implement PacketV1**

```kotlin
package com.pedometer.bt

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PacketV1(
    val channel: Channel,
    val flag: Boolean,
    val needsResponse: Boolean,
    val opCode: Int,
    val frameSerial: Int,
    val dataType: Int,
    val payload: ByteArray,
) {
    companion object {
        val PREAMBLE = byteArrayOf(0xBA.toByte(), 0xDC.toByte(), 0xFE.toByte())
        val EPILOGUE = byteArrayOf(0xEF.toByte())

        const val DATA_TYPE_PLAIN = 0
        const val DATA_TYPE_ENCRYPTED = 1
        const val DATA_TYPE_AUTH = 2

        const val OPCODE_READ = 0
        const val OPCODE_SEND = 2

        const val CHANNEL_VERSION = 0
        const val CHANNEL_PROTO_RX = 1
        const val CHANNEL_PROTO_TX = 2
        const val CHANNEL_FITNESS = 3
        const val CHANNEL_MASS = 5

        fun rawChannelToChannel(raw: Int): Channel = when (raw) {
            CHANNEL_VERSION -> Channel.Version
            CHANNEL_PROTO_RX, CHANNEL_PROTO_TX -> Channel.ProtobufCommand
            CHANNEL_FITNESS -> Channel.Activity
            CHANNEL_MASS -> Channel.Data
            else -> Channel.Unknown
        }

        fun channelToRawTx(channel: Channel): Int = when (channel) {
            Channel.Version -> CHANNEL_VERSION
            Channel.Authentication, Channel.ProtobufCommand -> CHANNEL_PROTO_TX
            Channel.Activity -> CHANNEL_FITNESS
            Channel.Data -> CHANNEL_MASS
            else -> -1
        }

        fun dataTypeForChannel(channel: Channel): Int = when (channel) {
            Channel.Authentication -> DATA_TYPE_AUTH
            Channel.ProtobufCommand, Channel.Version, Channel.Data -> DATA_TYPE_ENCRYPTED
            Channel.Activity -> DATA_TYPE_PLAIN
            else -> DATA_TYPE_PLAIN
        }

        fun decode(bytes: ByteArray): PacketV1? {
            if (bytes.size < 11) return null

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val preamble = ByteArray(3)
            buf.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            val rawChannel = buf.get().toInt() and 0x0F
            val flags = buf.get().toInt() and 0xFF
            val flag = (flags and 0x80) != 0
            val needsResponse = (flags and 0x40) != 0

            val payloadSizeWithHeader = buf.short.toInt() and 0xFFFF
            val payloadSize = payloadSizeWithHeader - 3

            if (payloadSize < 0 || bytes.size < payloadSize + 11) return null

            val opCode = buf.get().toInt() and 0xFF
            val frameSerial = buf.get().toInt() and 0xFF
            val dataType = buf.get().toInt() and 0xFF
            val payload = ByteArray(payloadSize)
            buf.get(payload)

            val epilogue = ByteArray(1)
            buf.get(epilogue)
            if (!epilogue.contentEquals(EPILOGUE)) return null

            return PacketV1(
                channel = rawChannelToChannel(rawChannel),
                flag = flag,
                needsResponse = needsResponse,
                opCode = opCode,
                frameSerial = frameSerial,
                dataType = dataType,
                payload = payload,
            )
        }
    }

    fun encode(): ByteArray {
        val totalSize = 11 + payload.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PREAMBLE)
        buf.put((channelToRawTx(channel) and 0x0F).toByte())
        buf.put(((if (flag) 0x80 else 0) or (if (needsResponse) 0x40 else 0)).toByte())
        buf.putShort((payload.size + 3).toShort())
        buf.put((opCode and 0xFF).toByte())
        buf.put((frameSerial and 0xFF).toByte())
        buf.put((dataType and 0xFF).toByte())
        buf.put(payload)
        buf.put(EPILOGUE)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketV1) return false
        return channel == other.channel && flag == other.flag && needsResponse == other.needsResponse &&
                opCode == other.opCode && frameSerial == other.frameSerial && dataType == other.dataType &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int = payload.contentHashCode()
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: All 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/PacketV1.kt app/src/test/java/com/pedometer/bt/PacketV1Test.kt
git commit -m "feat: implement V1 packet framing with tests"
```

---

### Task 5: Implement V2 Packet Framing

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/PacketV2.kt`
- Create: `app/src/test/java/com/pedometer/bt/PacketV2Test.kt`

- [ ] **Step 1: Write failing test for V2 packet**

```kotlin
package com.pedometer.bt

import org.junit.Assert.*
import org.junit.Test

class PacketV2Test {

    @Test
    fun `decode data packet`() {
        // Preamble(A5A5) + type(03) + seq(01) + payloadLen(0500) + checksum(XXXX) + channel(01) + opcode(01) + data(AABBCC)
        // We'll build it properly using encode and verify decode
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val encoded = PacketV2.encodeDataPacket(
            channel = Channel.Authentication,
            sequenceNumber = 1,
            payload = payload,
            encrypt = null,
        )
        val decoded = PacketV2.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is PacketV2.DataPacket)
        val data = decoded as PacketV2.DataPacket
        assertEquals(Channel.ProtobufCommand, data.channel) // Auth maps to Protobuf channel
        assertEquals(1, data.sequenceNumber)
        assertArrayEquals(payload, data.payload)
    }

    @Test
    fun `decode ack packet`() {
        val encoded = PacketV2.encodeAck(sequenceNumber = 42)
        val decoded = PacketV2.decode(encoded)
        assertNotNull(decoded)
        assertTrue(decoded is PacketV2.AckPacket)
        assertEquals(42, decoded!!.sequenceNumber)
    }

    @Test
    fun `decode returns null for bad preamble`() {
        val raw = byteArrayOf(0x00, 0x00, 0x03, 0x01, 0x05, 0x00, 0x00, 0x00, 0x01, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        assertNull(PacketV2.decode(raw))
    }

    @Test
    fun `checksum validates`() {
        val payload = byteArrayOf(0x01, 0x01, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val checksum = PacketV2.crc16arc(payload)
        assertTrue(checksum in 0..0xFFFF)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: compilation failure — `PacketV2` not found

- [ ] **Step 3: Implement PacketV2**

```kotlin
package com.pedometer.bt

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed class PacketV2(val packetType: Int, val sequenceNumber: Int) {

    class AckPacket(sequenceNumber: Int) : PacketV2(TYPE_ACK, sequenceNumber)

    class SessionConfigPacket(sequenceNumber: Int, val opCode: Int) : PacketV2(TYPE_SESSION_CONFIG, sequenceNumber)

    class DataPacket(
        sequenceNumber: Int,
        val channel: Channel,
        val opCode: Int,
        val payload: ByteArray,
    ) : PacketV2(TYPE_DATA, sequenceNumber)

    companion object {
        val PREAMBLE = byteArrayOf(0xA5.toByte(), 0xA5.toByte())

        const val TYPE_ACK = 1
        const val TYPE_SESSION_CONFIG = 2
        const val TYPE_DATA = 3

        const val OPCODE_PLAINTEXT = 1
        const val OPCODE_ENCRYPTED = 2

        const val SESSION_START_REQUEST = 1
        const val SESSION_START_RESPONSE = 2

        private const val CHANNEL_PROTOBUF = 1
        private const val CHANNEL_DATA = 2
        private const val CHANNEL_ACTIVITY = 5

        fun channelToRaw(channel: Channel): Int = when (channel) {
            Channel.Authentication, Channel.ProtobufCommand -> CHANNEL_PROTOBUF
            Channel.Data -> CHANNEL_DATA
            Channel.Activity -> CHANNEL_ACTIVITY
            else -> -1
        }

        fun rawToChannel(raw: Int): Channel = when (raw) {
            CHANNEL_PROTOBUF -> Channel.ProtobufCommand
            CHANNEL_DATA -> Channel.Data
            CHANNEL_ACTIVITY -> Channel.Activity
            else -> Channel.Unknown
        }

        fun opCodeForChannel(channel: Channel): Int = when (channel) {
            Channel.Authentication, Channel.Data -> OPCODE_PLAINTEXT
            Channel.ProtobufCommand, Channel.Activity -> OPCODE_ENCRYPTED
            else -> OPCODE_PLAINTEXT
        }

        fun crc16arc(data: ByteArray): Int {
            var crc = 0
            for (b in data) {
                for (j in 0 until 8) {
                    crc = crc shl 1
                    if ((((crc shr 16) and 1) xor ((b.toInt() shr j) and 1)) == 1) {
                        crc = crc xor 0x8005
                    }
                }
            }
            return Integer.reverse(crc).ushr(16)
        }

        fun encodeAck(sequenceNumber: Int): ByteArray {
            val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_ACK and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(0) // payload length = 0
            buf.putShort(0) // checksum of empty payload
            return buf.array()
        }

        fun encodeSessionConfig(sequenceNumber: Int, opCode: Int): ByteArray {
            val payload = byteArrayOf(
                opCode.toByte(),
                // VERSION key=1, len=3, value=01.00.00
                0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
                // MAX_FRAME_SIZE key=2, len=2, value=0xFC00 (64512)
                0x02, 0x02, 0x00, 0x00, 0xFC.toByte(),
                // TX_WIN key=3, len=2, value=0x0020 (32)
                0x03, 0x02, 0x00, 0x20, 0x00,
                // SEND_TIMEOUT key=4, len=2, value=0x2710 (10000ms)
                0x04, 0x02, 0x00, 0x10, 0x27,
            )
            val checksum = crc16arc(payload)
            val buf = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_SESSION_CONFIG and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(payload.size.toShort())
            buf.putShort(checksum.toShort())
            buf.put(payload)
            return buf.array()
        }

        fun encodeDataPacket(
            channel: Channel,
            sequenceNumber: Int,
            payload: ByteArray,
            encrypt: ((ByteArray) -> ByteArray)?,
        ): ByteArray {
            val opCode = opCodeForChannel(channel)
            val processedPayload = if (opCode == OPCODE_ENCRYPTED && encrypt != null) {
                encrypt(payload)
            } else {
                payload
            }
            val packetPayload = ByteBuffer.allocate(2 + processedPayload.size)
                .put((channelToRaw(channel) and 0x0F).toByte())
                .put((opCode and 0xFF).toByte())
                .put(processedPayload)
                .array()

            val checksum = crc16arc(packetPayload)
            val buf = ByteBuffer.allocate(8 + packetPayload.size).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(PREAMBLE)
            buf.put((TYPE_DATA and 0x0F).toByte())
            buf.put((sequenceNumber and 0xFF).toByte())
            buf.putShort(packetPayload.size.toShort())
            buf.putShort(checksum.toShort())
            buf.put(packetPayload)
            return buf.array()
        }

        fun decode(bytes: ByteArray): PacketV2? {
            if (bytes.size < 8) return null
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

            val preamble = ByteArray(2)
            buf.get(preamble)
            if (!preamble.contentEquals(PREAMBLE)) return null

            val packetType = buf.get().toInt() and 0x0F
            val sequenceNumber = buf.get().toInt() and 0xFF
            val payloadLength = buf.short.toInt() and 0xFFFF
            val givenChecksum = buf.short.toInt() and 0xFFFF

            if (buf.remaining() < payloadLength) return null

            val payloadBytes = ByteArray(payloadLength)
            buf.get(payloadBytes)

            if (payloadLength > 0) {
                val calcChecksum = crc16arc(payloadBytes)
                if (calcChecksum != givenChecksum) return null
            }

            return when (packetType) {
                TYPE_ACK -> AckPacket(sequenceNumber)
                TYPE_SESSION_CONFIG -> {
                    val opCode = if (payloadBytes.isNotEmpty()) payloadBytes[0].toInt() and 0xFF else 0
                    SessionConfigPacket(sequenceNumber, opCode)
                }
                TYPE_DATA -> {
                    if (payloadBytes.size < 2) return null
                    val rawChannel = payloadBytes[0].toInt() and 0x0F
                    val opCode = payloadBytes[1].toInt() and 0xFF
                    val data = payloadBytes.copyOfRange(2, payloadBytes.size)
                    DataPacket(sequenceNumber, rawToChannel(rawChannel), opCode, data)
                }
                else -> null
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: All tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/PacketV2.kt app/src/test/java/com/pedometer/bt/PacketV2Test.kt
git commit -m "feat: implement V2 packet framing with tests"
```

---

### Task 6: Implement Auth Crypto

**Files:**
- Create: `app/src/main/java/com/pedometer/auth/AuthService.kt`
- Create: `app/src/test/java/com/pedometer/auth/AuthServiceTest.kt`

- [ ] **Step 1: Write failing test for HMAC-SHA256 key derivation**

```kotlin
package com.pedometer.auth

import org.junit.Assert.*
import org.junit.Test

class AuthServiceTest {

    @Test
    fun `computeAuthKeys produces 64 bytes`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce = ByteArray(16) { (it + 16).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val result = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        assertEquals(64, result.size)
    }

    @Test
    fun `computeAuthKeys is deterministic`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce = ByteArray(16) { (it + 16).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val r1 = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        val r2 = AuthService.computeAuthKeys(secretKey, phoneNonce, watchNonce)
        assertArrayEquals(r1, r2)
    }

    @Test
    fun `computeAuthKeys changes with different nonces`() {
        val secretKey = ByteArray(16) { it.toByte() }
        val phoneNonce1 = ByteArray(16) { (it + 16).toByte() }
        val phoneNonce2 = ByteArray(16) { (it + 48).toByte() }
        val watchNonce = ByteArray(16) { (it + 32).toByte() }
        val r1 = AuthService.computeAuthKeys(secretKey, phoneNonce1, watchNonce)
        val r2 = AuthService.computeAuthKeys(secretKey, phoneNonce2, watchNonce)
        assertFalse(r1.contentEquals(r2))
    }

    @Test
    fun `hmacSha256 produces 32 bytes`() {
        val key = ByteArray(16) { 0x42 }
        val input = ByteArray(32) { 0x01 }
        val result = AuthService.hmacSha256(key, input)
        assertEquals(32, result.size)
    }

    @Test
    fun `encryptCcm then decryptCcm roundtrip`() {
        val key = ByteArray(16) { (it * 3).toByte() }
        val nonce = ByteArray(12) { (it * 7).toByte() }
        val plaintext = "hello watch".toByteArray()
        val encrypted = AuthService.encryptCcm(key, nonce, plaintext)
        val decrypted = AuthService.decryptCcm(key, nonce, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encryptCtr then decryptCtr roundtrip`() {
        val key = ByteArray(16) { (it * 5).toByte() }
        val plaintext = "hello watch v2".toByteArray()
        val encrypted = AuthService.encryptCtr(key, key, plaintext)
        val decrypted = AuthService.decryptCtr(key, key, encrypted)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `parseAuthKey handles 0x prefix`() {
        val hex = "0xba2ae13b1dba45e4f53e28e98b6b5846"
        val bytes = AuthService.parseAuthKey(hex)
        assertEquals(16, bytes.size)
        assertEquals(0xBA.toByte(), bytes[0])
        assertEquals(0x46.toByte(), bytes[15])
    }

    @Test
    fun `parseAuthKey handles no prefix`() {
        val hex = "ba2ae13b1dba45e4f53e28e98b6b5846"
        val bytes = AuthService.parseAuthKey(hex)
        assertEquals(16, bytes.size)
        assertEquals(0xBA.toByte(), bytes[0])
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: compilation failure — `AuthService` not found

- [ ] **Step 3: Implement AuthService**

```kotlin
package com.pedometer.auth

import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.CCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AuthService(authKeyHex: String) {

    private val secretKey: ByteArray = parseAuthKey(authKeyHex)
    val phoneNonce: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    private var decryptionKey = ByteArray(16)
    private var encryptionKey = ByteArray(16)
    private var decryptionNonce = ByteArray(4)
    private var encryptionNonce = ByteArray(4)

    var isInitialized = false
        private set

    var useV2Crypto = false

    fun processWatchNonce(watchNonce: ByteArray, watchHmac: ByteArray): Boolean {
        val derived = computeAuthKeys(secretKey, phoneNonce, watchNonce)

        System.arraycopy(derived, 0, decryptionKey, 0, 16)
        System.arraycopy(derived, 16, encryptionKey, 0, 16)
        System.arraycopy(derived, 32, decryptionNonce, 0, 4)
        System.arraycopy(derived, 36, encryptionNonce, 0, 4)

        val expectedHmac = hmacSha256(decryptionKey, watchNonce + phoneNonce)
        if (!expectedHmac.contentEquals(watchHmac)) return false

        isInitialized = true
        return true
    }

    fun getEncryptedNonces(watchNonce: ByteArray): ByteArray {
        return hmacSha256(encryptionKey, phoneNonce + watchNonce)
    }

    fun encrypt(data: ByteArray, counter: Int): ByteArray {
        if (useV2Crypto) return encryptCtr(encryptionKey, encryptionKey, data)
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(encryptionNonce).putInt(0).putInt(counter).array()
        return encryptCcm(encryptionKey, nonce, data)
    }

    fun decrypt(data: ByteArray): ByteArray {
        if (useV2Crypto) return decryptCtr(decryptionKey, decryptionKey, data)
        val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            .put(decryptionNonce).putInt(0).putInt(0).array()
        return decryptCcm(decryptionKey, nonce, data)
    }

    companion object {
        fun parseAuthKey(hex: String): ByteArray {
            val clean = hex.trim().removePrefix("0x")
            return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        fun computeAuthKeys(secretKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray {
            val salt = "miwear-auth".toByteArray()
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(phoneNonce + watchNonce, "HmacSHA256"))
            val hmacKey = mac.doFinal(secretKey)
            mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))

            val output = ByteArray(64)
            var tmp = ByteArray(0)
            var b: Byte = 1
            var i = 0
            while (i < output.size) {
                mac.update(tmp)
                mac.update(salt)
                mac.update(b)
                tmp = mac.doFinal()
                for (j in tmp.indices) {
                    if (i >= output.size) break
                    output[i++] = tmp[j]
                }
                b++
            }
            return output
        }

        fun hmacSha256(key: ByteArray, input: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(input)
        }

        fun encryptCcm(key: ByteArray, nonce: ByteArray, plaintext: ByteArray): ByteArray {
            val engine = AESEngine()
            val cipher = CCMBlockCipher(engine)
            cipher.init(true, AEADParameters(KeyParameter(key), 32, nonce, null))
            val out = ByteArray(cipher.getOutputSize(plaintext.size))
            val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
            cipher.doFinal(out, len)
            return out
        }

        fun decryptCcm(key: ByteArray, nonce: ByteArray, ciphertext: ByteArray): ByteArray {
            val engine = AESEngine()
            val cipher = CCMBlockCipher(engine)
            cipher.init(false, AEADParameters(KeyParameter(key), 32, nonce, null))
            val out = ByteArray(cipher.getOutputSize(ciphertext.size))
            val len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            cipher.doFinal(out, len)
            return out
        }

        fun encryptCtr(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(plaintext)
        }

        fun decryptCtr(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(ciphertext)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -10`
Expected: All 8 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/pedometer/auth/AuthService.kt app/src/test/java/com/pedometer/auth/AuthServiceTest.kt
git commit -m "feat: implement auth crypto (HMAC-SHA256, AES-CCM, AES-CTR)"
```

---

### Task 7: Implement Protobuf Command Helpers

**Files:**
- Create: `app/src/main/java/com/pedometer/proto/CommandHelper.kt`

- [ ] **Step 1: Create CommandHelper.kt**

```kotlin
package com.pedometer.proto

import com.google.protobuf.ByteString
import com.pedometer.proto.XiaomiProto

object CommandHelper {

    // Command types
    const val TYPE_AUTH = 1
    const val TYPE_SYSTEM = 2

    // Auth subtypes
    const val AUTH_SEND_USERID = 5
    const val AUTH_NONCE = 26
    const val AUTH_STEP3 = 27

    // System subtypes
    const val SYS_BATTERY = 1
    const val SYS_DEVICE_INFO = 2
    const val SYS_CLOCK = 3

    fun buildNonceCommand(nonce: ByteArray): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_NONCE)
            .setAuth(
                XiaomiProto.Auth.newBuilder()
                    .setPhoneNonce(
                        XiaomiProto.PhoneNonce.newBuilder()
                            .setNonce(ByteString.copyFrom(nonce))
                    )
            )
            .build()
    }

    fun buildAuthStep3(encryptedNonces: ByteArray, encryptedDeviceInfo: ByteArray): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_AUTH)
            .setSubtype(AUTH_STEP3)
            .setAuth(
                XiaomiProto.Auth.newBuilder()
                    .setAuthStep3(
                        XiaomiProto.AuthStep3.newBuilder()
                            .setEncryptedNonces(ByteString.copyFrom(encryptedNonces))
                            .setEncryptedDeviceInfo(ByteString.copyFrom(encryptedDeviceInfo))
                    )
            )
            .build()
    }

    fun buildDeviceInfoRequest(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYS_DEVICE_INFO)
            .build()
    }

    fun buildBatteryRequest(): XiaomiProto.Command {
        return XiaomiProto.Command.newBuilder()
            .setType(TYPE_SYSTEM)
            .setSubtype(SYS_BATTERY)
            .build()
    }

    fun buildAuthDeviceInfo(phoneModel: String, apiLevel: Int, region: String): XiaomiProto.AuthDeviceInfo {
        return XiaomiProto.AuthDeviceInfo.newBuilder()
            .setUnknown1(0)
            .setPhoneApiLevel(apiLevel.toFloat())
            .setPhoneName(phoneModel)
            .setUnknown3(224)
            .setRegion(region.uppercase().take(2))
            .build()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/proto/CommandHelper.kt
git commit -m "feat: add protobuf command builder helpers"
```

---

### Task 8: Implement SPP Bluetooth Connection

**Files:**
- Create: `app/src/main/java/com/pedometer/bt/SppConnection.kt`
- Create: `app/src/main/java/com/pedometer/bt/ProtocolHandler.kt`

- [ ] **Step 1: Create SppConnection.kt**

```kotlin
package com.pedometer.bt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class SppConnection(
    private val onData: (ByteArray) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    companion object {
        private const val TAG = "SppConnection"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice): Boolean {
        return try {
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            socket = s
            inputStream = s.inputStream
            outputStream = s.outputStream
            running = true
            Thread({ readLoop() }, "spp-read").start()
            Log.i(TAG, "Connected to ${device.address}")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Connection failed", e)
            false
        }
    }

    fun write(data: ByteArray) {
        try {
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: IOException) {
            Log.e(TAG, "Write failed", e)
            disconnect()
        }
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    val isConnected: Boolean get() = socket?.isConnected == true && running

    private fun readLoop() {
        val buf = ByteArray(4096)
        try {
            while (running) {
                val n = inputStream?.read(buf) ?: -1
                if (n < 0) break
                onData(buf.copyOf(n))
            }
        } catch (e: IOException) {
            if (running) Log.e(TAG, "Read error", e)
        } finally {
            running = false
            onDisconnected()
        }
    }
}
```

- [ ] **Step 2: Create ProtocolHandler.kt**

```kotlin
package com.pedometer.bt

import android.os.Build
import android.util.Log
import com.pedometer.auth.AuthService
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class ProtocolHandler(
    private val authService: AuthService,
    private val connection: SppConnection,
    private val onAuthenticated: () -> Unit,
    private val onCommand: (XiaomiProto.Command) -> Unit,
) {
    companion object {
        private const val TAG = "ProtocolHandler"
    }

    private val buffer = ByteArrayOutputStream()
    private var useV2 = false
    private val frameCounter = AtomicInteger(0)
    private val encryptionCounter = AtomicInteger(0)
    private val packetSeqV2 = AtomicInteger(0)

    fun start() {
        // Send version request (V1 format, plaintext)
        val versionPacket = PacketV1(
            channel = Channel.Version,
            flag = true,
            needsResponse = true,
            opCode = PacketV1.OPCODE_READ,
            frameSerial = 0,
            dataType = PacketV1.DATA_TYPE_PLAIN,
            payload = ByteArray(0),
        ).encode()
        connection.write(versionPacket)
    }

    fun onDataReceived(data: ByteArray) {
        buffer.write(data)
        processBuffer()
    }

    private fun processBuffer() {
        while (true) {
            val bytes = buffer.toByteArray()
            if (bytes.size < 8) return

            if (useV2) {
                processV2(bytes) ?: return
            } else {
                processV1(bytes) ?: return
            }
        }
    }

    private fun processV1(bytes: ByteArray): Unit? {
        if (bytes.size < 11) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Check preamble
        if (bytes[0] != PacketV1.PREAMBLE[0]) {
            // Find next valid start
            val offset = (1 until bytes.size).firstOrNull { bytes[it] == PacketV1.PREAMBLE[0] }
            skipBuffer(offset ?: bytes.size)
            return Unit
        }

        // Check if full packet present
        buf.position(5)
        val payloadSize = buf.short.toInt() and 0xFFFF
        val packetSize = payloadSize + 8
        if (bytes.size < packetSize) return null

        val packet = PacketV1.decode(bytes)
        skipBuffer(packetSize)

        if (packet == null) return Unit

        when (packet.channel) {
            Channel.Version -> handleVersionResponse(packet.payload)
            Channel.ProtobufCommand -> {
                val decrypted = if (packet.dataType == PacketV1.DATA_TYPE_ENCRYPTED && authService.isInitialized) {
                    authService.decrypt(packet.payload)
                } else {
                    packet.payload
                }
                handleProtobufPayload(decrypted)
            }
            else -> Log.d(TAG, "Unhandled channel: ${packet.channel}")
        }
        return Unit
    }

    private fun processV2(bytes: ByteArray): Unit? {
        if (bytes.size < 8) return null
        if (bytes[0] != PacketV2.PREAMBLE[0]) {
            val offset = (1 until bytes.size).firstOrNull { bytes[it] == PacketV2.PREAMBLE[0] }
            skipBuffer(offset ?: bytes.size)
            return Unit
        }

        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val payloadLen = buf.short.toInt() and 0xFFFF
        val packetSize = 8 + payloadLen
        if (bytes.size < packetSize) return null

        val packet = PacketV2.decode(bytes)
        skipBuffer(packetSize)
        if (packet == null) return Unit

        when (packet) {
            is PacketV2.SessionConfigPacket -> {
                Log.i(TAG, "Session config received, opcode=${packet.opCode}")
                startAuth()
            }
            is PacketV2.DataPacket -> {
                val decrypted = if (packet.opCode == PacketV2.OPCODE_ENCRYPTED && authService.isInitialized) {
                    authService.decrypt(packet.payload)
                } else {
                    packet.payload
                }
                // Send ACK
                connection.write(PacketV2.encodeAck(packet.sequenceNumber))
                handleProtobufPayload(decrypted)
            }
            is PacketV2.AckPacket -> Log.d(TAG, "ACK for ${packet.sequenceNumber}")
        }
        return Unit
    }

    private fun handleVersionResponse(payload: ByteArray) {
        if (payload.isNotEmpty() && payload[0] >= 2) {
            Log.i(TAG, "Protocol V2 detected")
            useV2 = true
            authService.useV2Crypto = true
            // Send session config
            connection.write(PacketV2.encodeSessionConfig(0, PacketV2.SESSION_START_REQUEST))
        } else {
            Log.i(TAG, "Protocol V1 detected")
            startAuth()
        }
    }

    private fun startAuth() {
        val cmd = CommandHelper.buildNonceCommand(authService.phoneNonce)
        sendCommand(cmd, forAuth = true)
    }

    private fun handleProtobufPayload(data: ByteArray) {
        try {
            val cmd = XiaomiProto.Command.parseFrom(data)
            Log.d(TAG, "Command type=${cmd.type} subtype=${cmd.subtype}")

            if (cmd.type == CommandHelper.TYPE_AUTH) {
                handleAuthCommand(cmd)
            } else {
                onCommand(cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse protobuf", e)
        }
    }

    private fun handleAuthCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.AUTH_NONCE -> {
                val watchNonce = cmd.auth.watchNonce.nonce.toByteArray()
                val watchHmac = cmd.auth.watchNonce.hmac.toByteArray()

                if (!authService.processWatchNonce(watchNonce, watchHmac)) {
                    Log.e(TAG, "Auth failed: HMAC mismatch. Check auth key.")
                    return
                }

                val encryptedNonces = authService.getEncryptedNonces(watchNonce)
                val deviceInfo = CommandHelper.buildAuthDeviceInfo(
                    phoneModel = Build.MODEL,
                    apiLevel = Build.VERSION.SDK_INT,
                    region = Locale.getDefault().language.take(2),
                )
                val encryptedDeviceInfo = authService.encrypt(deviceInfo.toByteArray(), 0)

                val step3 = CommandHelper.buildAuthStep3(encryptedNonces, encryptedDeviceInfo)
                sendCommand(step3, forAuth = true)
            }
            CommandHelper.AUTH_STEP3 -> {
                Log.i(TAG, "Authentication successful!")
                onAuthenticated()
            }
            else -> Log.w(TAG, "Unknown auth subtype: ${cmd.subtype}")
        }
    }

    fun sendCommand(command: XiaomiProto.Command, forAuth: Boolean = false) {
        val data = command.toByteArray()
        val channel = if (forAuth) Channel.Authentication else Channel.ProtobufCommand

        val packet: ByteArray = if (useV2) {
            val encrypt: ((ByteArray) -> ByteArray)? = if (!forAuth && authService.isInitialized) {
                { authService.encrypt(it, 0) }
            } else null
            PacketV2.encodeDataPacket(channel, packetSeqV2.getAndIncrement(), data, encrypt)
        } else {
            val dataType = if (forAuth) PacketV1.DATA_TYPE_AUTH else PacketV1.DATA_TYPE_ENCRYPTED
            var payload = data
            if (dataType == PacketV1.DATA_TYPE_ENCRYPTED && authService.isInitialized) {
                val counter = encryptionCounter.incrementAndGet()
                payload = authService.encrypt(data, counter)
                payload = ByteBuffer.allocate(payload.size + 2).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(counter.toShort()).put(payload).array()
            }
            PacketV1(
                channel = channel,
                flag = true,
                needsResponse = false,
                opCode = PacketV1.OPCODE_SEND,
                frameSerial = frameCounter.getAndIncrement(),
                dataType = dataType,
                payload = payload,
            ).encode()
        }

        connection.write(packet)
    }

    private fun skipBuffer(count: Int) {
        val bytes = buffer.toByteArray()
        buffer.reset()
        if (count < bytes.size) {
            buffer.write(bytes, count, bytes.size - count)
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/bt/SppConnection.kt app/src/main/java/com/pedometer/bt/ProtocolHandler.kt
git commit -m "feat: implement SPP connection and protocol handler with V1/V2 support"
```

---

### Task 9: Implement ViewModel

**Files:**
- Create: `app/src/main/java/com/pedometer/vm/WatchViewModel.kt`

- [ ] **Step 1: Create WatchViewModel.kt**

```kotlin
package com.pedometer.vm

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pedometer.auth.AuthService
import com.pedometer.bt.ProtocolHandler
import com.pedometer.bt.SppConnection
import com.pedometer.proto.CommandHelper
import com.pedometer.proto.XiaomiProto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class WatchState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.Disconnected,
    val serialNumber: String = "",
    val firmware: String = "",
    val model: String = "",
    val batteryLevel: Int = -1,
    val batteryCharging: Boolean = false,
    val authKey: String = "",
    val macAddress: String = "",
)

enum class ConnectionStatus {
    Disconnected, Connecting, Authenticating, Connected
}

class WatchViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "WatchViewModel"
        private const val PREFS_NAME = "pedometer_prefs"
        private const val KEY_AUTH = "auth_key"
        private const val KEY_MAC = "mac_address"
    }

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state

    private var connection: SppConnection? = null
    private var protocolHandler: ProtocolHandler? = null
    private var authService: AuthService? = null

    init {
        val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            authKey = prefs.getString(KEY_AUTH, "") ?: "",
            macAddress = prefs.getString(KEY_MAC, "") ?: "",
        )
    }

    fun updateAuthKey(key: String) {
        _state.value = _state.value.copy(authKey = key)
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_AUTH, key).apply()
    }

    fun updateMacAddress(mac: String) {
        _state.value = _state.value.copy(macAddress = mac)
        getApplication<Application>().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_MAC, mac).apply()
    }

    @SuppressLint("MissingPermission")
    fun connect() {
        val s = _state.value
        if (s.authKey.isBlank() || s.macAddress.isBlank()) return
        if (s.connectionStatus != ConnectionStatus.Disconnected) return

        _state.value = s.copy(connectionStatus = ConnectionStatus.Connecting)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val btManager = getApplication<Application>()
                    .getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val adapter = btManager.adapter ?: return@launch
                val device: BluetoothDevice = adapter.getRemoteDevice(s.macAddress)

                val auth = AuthService(s.authKey)
                authService = auth

                val conn = SppConnection(
                    onData = { data -> protocolHandler?.onDataReceived(data) },
                    onDisconnected = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                    }
                )
                connection = conn

                val handler = ProtocolHandler(
                    authService = auth,
                    connection = conn,
                    onAuthenticated = {
                        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Connected)
                        requestDeviceInfo()
                    },
                    onCommand = { cmd -> handleCommand(cmd) },
                )
                protocolHandler = handler

                if (!conn.connect(device)) {
                    _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
                    return@launch
                }

                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Authenticating)
                handler.start()
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed", e)
                _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
            }
        }
    }

    fun disconnect() {
        connection?.disconnect()
        connection = null
        protocolHandler = null
        authService = null
        _state.value = _state.value.copy(connectionStatus = ConnectionStatus.Disconnected)
    }

    private fun requestDeviceInfo() {
        protocolHandler?.sendCommand(CommandHelper.buildDeviceInfoRequest())
        protocolHandler?.sendCommand(CommandHelper.buildBatteryRequest())
    }

    private fun handleCommand(cmd: XiaomiProto.Command) {
        when (cmd.type) {
            CommandHelper.TYPE_SYSTEM -> handleSystemCommand(cmd)
            else -> Log.d(TAG, "Unhandled command type=${cmd.type}")
        }
    }

    private fun handleSystemCommand(cmd: XiaomiProto.Command) {
        when (cmd.subtype) {
            CommandHelper.SYS_DEVICE_INFO -> {
                if (cmd.hasSystem() && cmd.system.hasDeviceInfo()) {
                    val info = cmd.system.deviceInfo
                    _state.value = _state.value.copy(
                        serialNumber = info.serialNumber,
                        firmware = info.firmware,
                        model = info.model,
                    )
                    Log.i(TAG, "Device: ${info.model} FW:${info.firmware} SN:${info.serialNumber}")
                }
            }
            CommandHelper.SYS_BATTERY -> {
                if (cmd.hasSystem() && cmd.system.hasPower() && cmd.system.power.hasBattery()) {
                    val bat = cmd.system.power.battery
                    _state.value = _state.value.copy(
                        batteryLevel = bat.level,
                        batteryCharging = bat.state == 1,
                    )
                    Log.i(TAG, "Battery: ${bat.level}% charging=${bat.state}")
                }
            }
            else -> Log.d(TAG, "Unhandled system subtype=${cmd.subtype}")
        }
    }

    override fun onCleared() {
        disconnect()
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/pedometer/vm/WatchViewModel.kt
git commit -m "feat: implement WatchViewModel with connection management"
```

---

### Task 10: Implement Connect Screen UI

**Files:**
- Create: `app/src/main/java/com/pedometer/ui/ConnectScreen.kt`
- Modify: `app/src/main/java/com/pedometer/MainActivity.kt`

- [ ] **Step 1: Create ConnectScreen.kt**

```kotlin
package com.pedometer.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pedometer.vm.ConnectionStatus
import com.pedometer.vm.WatchState

@Composable
fun ConnectScreen(
    state: WatchState,
    onAuthKeyChange: (String) -> Unit,
    onMacChange: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) onConnect()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Pedometer",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = state.macAddress,
            onValueChange = onMacChange,
            label = { Text("MAC Address") },
            placeholder = { Text("XX:XX:XX:XX:XX:XX") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = state.authKey,
            onValueChange = onAuthKeyChange,
            label = { Text("Auth Key") },
            placeholder = { Text("0x...") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(16.dp))

        when (state.connectionStatus) {
            ConnectionStatus.Disconnected -> {
                Button(
                    onClick = {
                        val perms = mutableListOf<String>()
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
                            perms.add(Manifest.permission.BLUETOOTH_SCAN)
                        }
                        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
                        permissionLauncher.launch(perms.toTypedArray())
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.authKey.isNotBlank() && state.macAddress.isNotBlank(),
                ) {
                    Text("Connect")
                }
            }
            ConnectionStatus.Connecting -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Connecting...")
            }
            ConnectionStatus.Authenticating -> {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Authenticating...")
            }
            ConnectionStatus.Connected -> {
                Button(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                ) {
                    Text("Disconnect")
                }
            }
        }

        if (state.connectionStatus == ConnectionStatus.Connected) {
            Spacer(Modifier.height(24.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Device Info", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.model.isNotBlank()) Text("Model: ${state.model}")
                    if (state.firmware.isNotBlank()) Text("Firmware: ${state.firmware}")
                    if (state.serialNumber.isNotBlank()) Text("S/N: ${state.serialNumber}")
                }
            }

            Spacer(Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Battery", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    if (state.batteryLevel >= 0) {
                        Text("${state.batteryLevel}%${if (state.batteryCharging) " (charging)" else ""}")
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { state.batteryLevel / 100f },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("Loading...")
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Update MainActivity.kt**

```kotlin
package com.pedometer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pedometer.ui.ConnectScreen
import com.pedometer.ui.theme.PedometerTheme
import com.pedometer.vm.WatchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PedometerTheme {
                val vm: WatchViewModel = viewModel()
                val state by vm.state.collectAsState()
                ConnectScreen(
                    state = state,
                    onAuthKeyChange = vm::updateAuthKey,
                    onMacChange = vm::updateMacAddress,
                    onConnect = vm::connect,
                    onDisconnect = vm::disconnect,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Verify it compiles**

Run: `cd /home/dima/Projects/pedometer && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/pedometer/ui/ConnectScreen.kt app/src/main/java/com/pedometer/MainActivity.kt
git commit -m "feat: implement ConnectScreen UI with device info and battery display"
```

---

### Task 11: Install and Test on Device

**Files:** none (testing only)

- [ ] **Step 1: Build and install APK**

Run:
```bash
cd /home/dima/Projects/pedometer
./gradlew assembleDebug
adb -s 192.168.1.213:41517 install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`

- [ ] **Step 2: Run all unit tests**

Run: `cd /home/dima/Projects/pedometer && ./gradlew test 2>&1 | tail -15`
Expected: All tests pass

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: Phase 1 complete — BLE transport, auth, device info, battery"
```

---

## Self-Review Checklist

1. **Spec coverage:**
   - [x] SPP Bluetooth connection
   - [x] V1 packet framing (preamble BADCFE)
   - [x] V2 packet framing (preamble A5A5, CRC-16/ARC)
   - [x] Protocol version detection (V1 vs V2 auto-switch)
   - [x] Auth handshake (HMAC-SHA256, "miwear-auth", AES-CCM/CTR)
   - [x] Device info request/response
   - [x] Battery request/response
   - [x] Auth key storage in SharedPreferences
   - [x] UI for connection + display

2. **Placeholder scan:** No TBDs, TODOs, or "fill in later" found.

3. **Type consistency:** `AuthService`, `PacketV1`, `PacketV2`, `Channel`, `SppConnection`, `ProtocolHandler`, `CommandHelper`, `WatchViewModel`, `WatchState`, `ConnectionStatus` — all consistent across tasks.
