package aman.playbackengine

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import aman.playbackengine.enginecore.SessionJournal
import aman.playbackengine.ui.theme.ComposeEmptyActivityTheme

/**
 * Gracious crash screen showing the session journal and error.
 */
class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val error = intent.getStringExtra("error") ?: "Unknown Error"
        val journal = intent.getStringExtra("journal") ?: "Journal Empty (Captured in main process)"

        setContent {
            ComposeEmptyActivityTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.errorContainer) {
                    SelectionContainer {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(16.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("Application Crashed", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("We captured the session journal to help debug the issue.", style = MaterialTheme.typography.bodyMedium)
                            
                            Spacer(modifier = Modifier.height(24.dp))
                            
                            Text("Session Journal", modifier = Modifier.align(Alignment.Start), style = MaterialTheme.typography.titleMedium)
                            Box(modifier = Modifier.fillMaxWidth().background(Color.Black).padding(8.dp)) {
                                Text(journal, color = Color.Green, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            Text("Error Details", modifier = Modifier.align(Alignment.Start), style = MaterialTheme.typography.titleMedium)
                            Box(modifier = Modifier.fillMaxWidth().background(Color.DarkGray).padding(8.dp)) {
                                Text(error, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            }

                            Spacer(modifier = Modifier.height(32.dp))

                            Button(onClick = {
                                val intent = Intent(this@CrashActivity, MainActivity::class.java)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                startActivity(intent)
                                finish()
                            }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                                Icon(Icons.Default.Refresh, null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Restart Application")
                            }
                            
                            Spacer(modifier = Modifier.height(64.dp))
                        }
                    }
                }
            }
        }
    }
}
