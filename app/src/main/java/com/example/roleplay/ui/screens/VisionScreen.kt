package com.example.roleplay.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisionScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedImage by remember { mutableStateOf<ImageBitmap?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图像识别", fontWeight = FontWeight.Bold, color = Color.White) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF16161E), titleContentColor = Color.White)
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier.fillMaxSize().padding(paddingValues).background(Color(0xFF12121A)).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().height(220.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E2E))
            ) {
                if (selectedImage != null) {
                    Canvas(modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))) {
                        drawImage(image = selectedImage!!, alpha = 1f)
                    }
                } else {
                    Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Image, contentDescription = null, tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(48.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(text = "选择图像进行识别", color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                ExtendedFloatingActionButton(
                    onClick = {
                        isProcessing = true
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1500) }
                        selectedImage = createMockImage()
                        isProcessing = false
                    },
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.Image, contentDescription = null) },
                    text = { Text("相册") },
                    containerColor = Color(0xFF9C27B0)
                )
                ExtendedFloatingActionButton(
                    onClick = {
                        isProcessing = true
                        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(1500) }
                        selectedImage = createMockImage()
                        isProcessing = false
                    },
                    modifier = Modifier.weight(1f),
                    icon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                    text = { Text("拍照") },
                    containerColor = Color(0xFF2196F3)
                )
            }
            
            if (isProcessing) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    CircularProgressIndicator(color = Color(0xFF9C27B0), modifier = Modifier.size(24.dp))
                    Text("AI识别中...", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
    }
}

private fun createMockImage(): ImageBitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(300, 200, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.parseColor("#1a1a2e"))
    return bitmap.asImageBitmap()
}
