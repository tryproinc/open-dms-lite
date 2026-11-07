package com.trypro.opendms

import android.Manifest
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.trypro.opendms.ui.DayMvpScreen
import com.trypro.opendms.ui.theme.OpenDmsTheme

/**
 * DAY MVP-0の唯一のActivity。11_DAY_SMARTPHONE_MVP_PLAN.md 3.1により
 * 画面はActivity内の状態切り替え(PREFLIGHT/CALIBRATING/MONITORING/RESULT)で構成し、
 * 複数Activity/Navigationライブラリは導入しない。
 *
 * カメラ権限は本Activityで一元的に要求する。拒否された後は画面のボタンから求め直し、
 * 「今後表示しない」で拒否された後は端末の設定を開く(公開前レビュー R-07)。
 */
class MainActivity : ComponentActivity() {

    /**
     * PiP(小窓)中か。Composeへ渡して表示を切り替える(docs/18 §14-15)。
     *
     * PiP中のActivityは**「表示中」のまま**なのでプロセスは維持され、カメラも解析も続く。
     * したがってForeground Serviceは要らない ── FGSが必要になるのは完全な
     * バックグラウンド動作を認めるときで、PiPはそれとは別の話である。
     */
    private var isInPip by mutableStateOf(false)

    /**
     * 設定でPiPが許可されているか。Composeから書き込まれる。
     * 許可されていなければ自動遷移もしない(既定の挙動を変えない)。
     */
    private var pipAllowed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 11_DAY_SMARTPHONE_MVP_PLAN.md 2: 「Open DMSを画面前面に維持」が運用前提。
        // 画面消灯まで防いでしまうFGS/WakeLockの本格対応は後段(§4)だが、既定のスリープ
        // タイムアウトでActivityが止まりカメラ・判定が丸ごと停止するのはMVP-0でも許容できないため、
        // 画面表示中はスリープしないようにする(Activity非表示になれば通常通りOSに解除される)。
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            OpenDmsTheme {
                var hasCameraPermission by remember {
                    mutableStateOf(
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED,
                    )
                }
                // 拒否されたうえで、OSがもうダイアログを出さない状態か。設定を開くしか直す方法がない。
                var cameraPermanentlyDenied by remember { mutableStateOf(false) }
                val permissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    hasCameraPermission = granted
                    cameraPermanentlyDenied = !granted &&
                        !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
                }
                // 端末の設定で許可して戻ってきたとき(または取り消されたとき)に反映する。
                LifecycleResumeEffect(Unit) {
                    hasCameraPermission = ContextCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.CAMERA,
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasCameraPermission) cameraPermanentlyDenied = false
                    onPauseOrDispose { }
                }

                LaunchedEffect(Unit) {
                    if (!hasCameraPermission) {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                }

                DayMvpScreen(
                    hasCameraPermission = hasCameraPermission,
                    cameraPermissionPermanentlyDenied = cameraPermanentlyDenied,
                    onRequestCameraPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    isInPictureInPicture = isInPip,
                    onPictureInPictureAllowedChange = { allowed ->
                        pipAllowed = allowed
                        applyPipParams(allowed)
                    },
                    onRequestExitPictureInPicture = ::exitPipToFullScreen,
                )
            }
        }
    }

    /**
     * 自動遷移(Android 12以降)とアスペクト比を設定する。
     *
     * `setAutoEnterEnabled`はホームへ戻る/他アプリへ切り替える操作で**自動的に**PiPへ入る。
     * これが無いと[onUserLeaveHint]で自分で呼ぶことになり、遷移がぎこちない。
     */
    private fun applyPipParams(allowed: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val params = PictureInPictureParams.Builder()
            // 縦持ちの運用なので縦長にする。極端な比は端末が拒否するので3:4程度に留める。
            .setAspectRatio(Rational(3, 4))
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setAutoEnterEnabled(allowed)
                    setSeamlessResizeEnabled(false)
                }
            }
            .build()
        runCatching { setPictureInPictureParams(params) }
    }

    /**
     * Android 11以前は自動遷移が無いので、離脱の合図で自分から入る。
     * 12以降は`setAutoEnterEnabled`が担うので何もしない(二重に呼ぶと遷移が乱れる)。
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipAllowed) return
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip = isInPictureInPictureMode
    }

    /**
     * PiPから全画面へ戻す。**発報したときに呼ぶ**(docs/18 §14-15)。
     *
     * ASV眠気2.4.1は視覚による報知を必須としており、小窓のままでは要件を満たすとは言いにくい。
     * PiP中のActivityが自分を展開する公式な手段は無いので、**自分自身へIntentを投げる**
     * (これでPiPが解除され全画面に戻る、というのがAndroidの想定動作)。
     */
    private fun exitPipToFullScreen() {
        if (!isInPip) return
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        runCatching { startActivity(intent) }
    }
}
