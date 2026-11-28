package com.nobars.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nobars.jellyfin.NbJellyfin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.UserDto
import timber.log.Timber

const val HOME_SERVER = "192.168.68.102:8096"

@Composable
fun ServerScreen(nav: Nav) {
    val nb = NbJellyfin.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(nb.lastServer() ?: HOME_SERVER) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val found = remember { mutableStateListOf<NbJellyfin.Server>() }
    var searching by remember { mutableStateOf(true) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        // Android drops the server's broadcast replies unless the app holds a multicast lock
        val wifi = context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        val lock = wifi?.createMulticastLock("nobars-discovery")?.apply { setReferenceCounted(false) }
        try {
            lock?.acquire()
            withTimeoutOrNull(8_000) {
                nb.localServers().collect { s -> if (found.none { it.address == s.address }) found.add(s) }
            }
        } catch (e: Exception) {
            Timber.w(e, "NoBars: server discovery failed")
        } finally {
            lock?.release()
        }
        searching = false
    }

    fun connect(input: String) {
        busy = true
        error = null
        scope.launch {
            val server =
                try {
                    nb.checkServer(input)
                } catch (e: Exception) {
                    Timber.w(e, "NoBars: server check failed")
                    null
                }
            busy = false
            if (server == null) {
                error = "No Jellyfin server answered at $input. Check the address and that the phone is on the home Wi-Fi.\n\nDetails: ${nb.lastError ?: "-"}"
            } else {
                nav.open(Screen.SignIn(server))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("NoBars", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = NbRed)
        Text("Connect to your Jellyfin server", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text("On this Wi-Fi", style = MaterialTheme.typography.labelLarge)
        if (searching && found.isEmpty()) {
            Text("Looking…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!searching && found.isEmpty()) {
            Text("None found automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        found.forEach { s ->
            OutlinedButton(onClick = { connect(s.address) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("${s.name}  ·  ${s.address}")
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Server address") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { connect(address.trim()) }, enabled = !busy && address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Checking…" else "Connect")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun SignInScreen(
    server: NbJellyfin.Server,
    nav: Nav,
) {
    val nb = NbJellyfin.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(server) {
        users =
            try {
                nb.publicUsers(server)
            } catch (e: Exception) {
                Timber.w(e, "NoBars: public users failed")
                emptyList()
            }
        if (users.size == 1 && name.isEmpty()) name = users[0].name.orEmpty()
    }

    fun signIn() {
        busy = true
        error = null
        scope.launch {
            try {
                nb.signIn(server, name.trim(), password)
            } catch (e: Exception) {
                Timber.w(e, "NoBars: sign-in failed")
                error = "Sign-in failed. Check the name and password."
            }
            busy = false
        }
    }

    fun quickConnect() {
        error = null
        scope.launch {
            try {
                val qc = nb.quickConnectStart(server)
                code = qc.code
                // wait for approval in Jellyfin (up to 5 minutes)
                repeat(150) {
                    delay(2_000)
                    if (nb.quickConnectApproved(qc.secret)) {
                        nb.quickConnectFinish(server, qc.secret)
                        return@launch
                    }
                }
                code = null
                error = "The Quick Connect code ran out. Try again."
            } catch (e: Exception) {
                Timber.w(e, "NoBars: quick connect failed")
                code = null
                error = "Quick Connect isn't available. Turn it on in Jellyfin's dashboard, or use the password."
            }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = { nav.back() }) { Text("‹ Server") }
        Text(server.name, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(server.address + (server.version?.let { "  ·  Jellyfin $it" } ?: ""), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (users.isNotEmpty()) {
            Text("Who's watching?", style = MaterialTheme.typography.labelLarge)
            users.forEach { u ->
                val picked = u.name == name
                Text(
                    (if (picked) "● " else "○ ") + u.name.orEmpty(),
                    fontSize = 18.sp,
                    color = if (picked) NbRed else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().clickable { name = u.name.orEmpty() }.padding(vertical = 8.dp),
                )
            }
        }
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { signIn() }, enabled = !busy && name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "Signing in…" else "Sign in")
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        val c = code
        if (c == null) {
            OutlinedButton(onClick = { quickConnect() }, modifier = Modifier.fillMaxWidth()) { Text("Use Quick Connect") }
        } else {
            Text("Quick Connect code", style = MaterialTheme.typography.labelLarge)
            Text(c, fontSize = 40.sp, fontWeight = FontWeight.Bold, color = NbRed)
            Text(
                "Approve it in Jellyfin: on a TV or browser that's signed in, open your profile → Quick Connect and enter this code.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            CircularProgressIndicator()
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}
