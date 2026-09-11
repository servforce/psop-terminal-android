package com.rokid.cxrmsamples.activities.minicpm

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.rokid.cxrmsamples.ui.theme.PsopTheme
import java.io.File

class MiniCpmDemoActivity : ComponentActivity() {
    private val viewModel: MiniCpmDemoViewModel by viewModels()
    private var pendingCameraUri: Uri? = null

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent(),
        viewModel::selectImage,
    )

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) viewModel.selectImage(pendingCameraUri)
        pendingCameraUri = null
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) launchCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PsopTheme {
                MiniCpmDemoScreen(
                    viewModel = viewModel,
                    onBack = ::finish,
                    onPickImage = { galleryLauncher.launch("image/*") },
                    onTakePhoto = ::requestCamera,
                )
            }
        }
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val directory = File(cacheDir, "photos").apply { mkdirs() }
        val file = File.createTempFile("minicpm_", ".jpg", directory)
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            file,
        )
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }
}
