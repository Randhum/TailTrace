package ch.swhizkid.tailtrace.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.swhizkid.tailtrace.ui.theme.ThreatColors

/** Survivor-centred guidance when a tracker may be following you. */
@Composable
fun TrackerSafetyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    fun go(intent: Intent) = runCatching { ctx.startActivity(intent) }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                "If a tracker is following you",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "TailTrace is a detection tool, not a substitute for professional help. If you're in immediate danger, call emergency services.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        Step(1, "Get somewhere safe", "If you feel in danger, head to a public place or someone you trust before doing anything else.")
        Step(2, "Think before you remove it", "Turning a tracker off can alert whoever placed it and escalate the risk. If you might be in danger, consider documenting it and getting help first.")
        Step(3, "Document what you can", "Save when and where it was seen (use Export), and photograph the device if you find it. A dated record helps police and advocates.")
        Step(4, "Report it", "Contact local police. If this involves a partner or ex, a domestic-violence advocate can help you make a safety plan.")

        Spacer(Modifier.height(24.dp))
        Text("Get help (Switzerland)", fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 13.sp)

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:117"))) },
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ThreatColors.Red)
        ) {
            Icon(Icons.Filled.Call, null)
            Spacer(Modifier.size(8.dp))
            Text("Call 117 (police)", fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(10.dp))
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Die Dargebotene Hand", fontWeight = FontWeight.SemiBold)
                Text(
                    "Free, confidential listening — 143, 24/7.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:143"))) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Call, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Call 143")
                    }
                    OutlinedButton(onClick = { go(Intent(Intent.ACTION_DIAL, Uri.parse("tel:144"))) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("144 ambulance")
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "These open your phone's dialer — TailTrace itself does not place the call. Resources shown are Swiss; check what's available where you are.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Step(n: Int, title: String, body: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp)) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Text("$n", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
        Spacer(Modifier.size(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
