package app.revanced.manager.ui.viewmodel

import android.app.Application
import android.content.pm.PackageManager
import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import app.revanced.manager.Variables.patches
import app.revanced.manager.Variables.selectedAppPackage
import app.revanced.manager.Variables.selectedPatches
import app.revanced.manager.api.API
import app.revanced.manager.patcher.worker.PatcherWorker
import app.revanced.manager.ui.Resource
import app.revanced.patcher.data.Data
import app.revanced.patcher.extensions.PatchExtensions.compatiblePackages
import app.revanced.patcher.extensions.PatchExtensions.options
import app.revanced.patcher.extensions.PatchExtensions.patchName
import app.revanced.patcher.patch.Patch
import app.revanced.patcher.util.patch.impl.DexPatchBundle
import dalvik.system.DexClassLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import java.io.File

class PatcherViewModel(private val app: Application, private val api: API) : ViewModel() {
    private val workdir = createWorkDir()
    private lateinit var patchBundleFile: String
    private val tag = "ReVanced Manager"

    init {
        runBlocking {
            loadPatches()
            downloadIntegrations()
        }
    }

    fun selectPatch(patchId: String, state: Boolean) {
        if (state) selectedPatches.add(patchId)
        else selectedPatches.remove(patchId)
    }

    private suspend fun downloadIntegrations() {
        api.downloadIntegrations(workdir).renameTo(File(workdir,"integrations.apk"))
    }

    fun selectAllPatches(patchList: List<PatchClass>, selectAll: Boolean) {
        patchList.forEach { patch ->
            val patchId = patch.patch.patchName
            if (selectAll && !patch.unsupported) selectedPatches.add(patchId)
            else selectedPatches.remove(patchId)
        }
    }

    fun setOption(patch: PatchClass, key: String, value: String) {
        patch.patch.options?.set(key, value)
        for (option in patch.patch.options!!) {
            println(option.key + option.value + option.title + option.description)
        }
    }

    fun getOption(patch: PatchClass, key: String) {
        patch.patch.options?.get(key)
    }

    fun isPatchSelected(patchId: String): Boolean {
        return selectedPatches.contains(patchId)
    }

    fun anyPatchSelected(): Boolean {
        return !selectedPatches.isEmpty()
    }


    fun getSelectedPackageInfo() =
        if (selectedAppPackage.value.isPresent)
            app.packageManager.getPackageInfo(
                selectedAppPackage.value.get(),
                PackageManager.GET_META_DATA
            )
        else null

    fun getFilteredPatchesAndCheckOptions(): List<PatchClass> {
        return buildList {
            val selected = getSelectedPackageInfo() ?: return@buildList
            val (patches) = patches.value as? Resource.Success ?: return@buildList
            patches.forEach patch@{ patch ->
                var unsupported = false
                var hasPatchOptions = false
                if (patch.options != null) {
                    hasPatchOptions = true
                    Log.d(tag, "${patch.patchName} has patch options.")
                }
                patch.compatiblePackages?.forEach { pkg ->
                    // if we detect unsupported once, don't overwrite it
                    if (pkg.name == selected.packageName) {
                        if (!unsupported)
                            unsupported =
                                pkg.versions.isNotEmpty() && !pkg.versions.any { it == selected.versionName }
                        add(PatchClass(patch, unsupported, hasPatchOptions))
                    }
                }
            }
        }
    }

    private fun loadPatches() = viewModelScope.launch {
        try {
            val file = api.downloadPatchBundle(app.filesDir)
            patchBundleFile = file.absolutePath
            loadPatches0(file.absolutePath)
        } catch (e: Exception) {
            Log.e("ReVancedManager", "An error occurred while loading patches", e)
        }
    }


    private fun loadPatches0(path: String) {
        val patchClasses = DexPatchBundle(
            path, DexClassLoader(
                path,
                app.codeCacheDir.absolutePath,
                null,
                javaClass.classLoader
            )
        ).loadPatches()
        patches.value = Resource.Success(patchClasses)
        Log.d("ReVanced Manager", "Finished loading patches")
    }

    fun startPatcher() {
        WorkManager
            .getInstance(app)
            .enqueueUniqueWork(
                "patching",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequest.Builder(PatcherWorker::class.java)
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInputData(
                        androidx.work.Data.Builder()
                            .putString("workdir", workdir.toString())
                            .putString("input",
                                getSelectedPackageInfo()?.applicationInfo?.publicSourceDir
                            )
                            .build()).build()
            )
    }
    private fun createWorkDir(): File {
        return app.filesDir.resolve("tmp-${System.currentTimeMillis()}")
            .also { it.mkdirs() }
    }
}

@Parcelize
data class PatchClass(
    val patch: Class<out Patch<Data>>,
    val unsupported: Boolean,
    val hasPatchOptions: Boolean,
) : Parcelable