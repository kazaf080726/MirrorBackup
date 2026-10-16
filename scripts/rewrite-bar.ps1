$ErrorActionPreference = 'Stop'
$enc = New-Object System.Text.UTF8Encoding($false)
$f = 'D:\Users\Administrator\Documents\DeepSeek Workspace\MirrorBackup\app\src\main\java\com\kazaf\mirrorbackup\ui\port\FloatingBottomBar.kt'
$c = [System.IO.File]::ReadAllText($f, [System.Text.Encoding]::UTF8)
# normalize to LF
$c = $c.Replace("`r`n", "`n")

function Replace-All($pat, $rep) {
    $script:c = [regex]::Replace($script:c, $pat, $rep)
}

Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.Backdrop' 'import com.kyant.backdrop.Backdrop'
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.blur' 'import com.kyant.backdrop.effects.blur'
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.drawBackdrop' 'import com.kyant.backdrop.drawBackdrop'
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.layerBackdrop' 'import com.kyant.backdrop.backdrops.layerBackdrop'
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.rememberLayerBackdrop' 'import com.kyant.backdrop.backdrops.rememberLayerBackdrop'
Replace-All 'import com\.kazaf\.mirrorbackup\.ui\.port\.liquid\.InnerShadow' 'import com.kyant.backdrop.shadow.InnerShadow'
Replace-All "import com\.kazaf\.mirrorbackup\.ui\.port\.liquid\.innerShadow\n" ''
Replace-All 'import com\.kazaf\.mirrorbackup\.ui\.port\.liquid\.lens' 'import com.kyant.backdrop.effects.lens'
Replace-All 'import com\.kazaf\.mirrorbackup\.ui\.port\.liquid\.rememberCombinedBackdrop' 'import com.kyant.backdrop.backdrops.rememberCombinedBackdrop'
Replace-All 'import com\.kazaf\.mirrorbackup\.ui\.port\.liquid\.vibrancy' 'import com.kyant.backdrop.effects.vibrancy'
Replace-All "import top\.yukonga\.miuix\.kmp\.blur\.highlight\.BloomStroke\n" ''
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.highlight\.Highlight' ("import com.kyant.backdrop.highlight.Highlight`n" + 'import com.kyant.backdrop.highlight.HighlightStyle')
Replace-All "import top\.yukonga\.miuix\.kmp\.blur\.highlight\.LightPosition\n" ''
Replace-All "import top\.yukonga\.miuix\.kmp\.blur\.highlight\.LightSource\n" ''
Replace-All 'import top\.yukonga\.miuix\.kmp\.blur\.sensor\.rememberDeviceTilt' ("import com.kazaf.mirrorbackup.ui.port.UISensor`n" + 'import com.kazaf.mirrorbackup.ui.port.rememberUISensor')

$oldSpec = @'
private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = BloomStroke(
        color = Color.White.copy(alpha = 0.12f),
        innerBlurRadius = 2.0.dp,
        primaryLight = LightSource(
            position = LightPosition(0.5f, -0.3f, -0.05f),
            color = Color.White,
            intensity = 1f,
        ),
        secondaryLight = LightSource(
            position = LightPosition(0.5f, 0.8f, -0.5f),
            color = Color.White,
            intensity = 0.4f,
        ),
        dualPeak = true,
    ),
)
'@
$newSpec = @'
private val iosIndicatorSpecular: Highlight = Highlight(
    width = 1.dp,
    alpha = 1f,
    style = HighlightStyle.Default(
        intensity = 0.55f,
        angle = 45f,
        falloff = 1f,
    ),
)
'@
$c = $c.Replace($oldSpec, $newSpec)

$oldTilt = @'
    val tiltState = rememberDeviceTilt()
    return remember(tiltState) {
        derivedStateOf {
            val tilt = tiltState.value
            val magnitudeSquared = tilt.gravityX * tilt.gravityX + tilt.gravityY * tilt.gravityY
'@
$newTilt = @'
    val uiSensor = rememberUISensor()
    return remember(uiSensor) {
        derivedStateOf {
            val tilt = uiSensor.gravity
            val magnitudeSquared = tilt.x * tilt.x + tilt.y * tilt.y
'@
$c = $c.Replace($oldTilt, $newTilt)
$c = $c.Replace('(atan2(tilt.gravityY, tilt.gravityX)', '(atan2(tilt.y, tilt.x)')

$oldRot = @'
    val baseStyle = base.style as BloomStroke
    val angle = rememberQuantizedGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            val basePrimary = baseStyle.primaryLight
            val rad = angle.value + (extraDegrees * PI / 180.0).toFloat()
            base.copy(
                style = baseStyle.copy(
                    primaryLight = basePrimary.copy(
                        position = LightPosition(
                            x = LIGHT_REF_X + cos(rad),
                            y = LIGHT_REF_Y + sin(rad),
                            z = basePrimary.position.z,
                        ),
                    ),
                ),
            )
        }
    }
'@
$newRot = @'
    val baseStyle = base.style as HighlightStyle.Default
    val angle = rememberQuantizedGravityAngle()
    return remember(angle, base, extraDegrees) {
        derivedStateOf {
            base.copy(style = baseStyle.copy(angle = angle.value + extraDegrees))
        }
    }
'@
$c = $c.Replace($oldRot, $newRot)

$c = $c.Replace('blur(4.dp.toPx(), 4.dp.toPx())', 'blur(4.dp.toPx())')
$c = $c.Replace('chromaticAberration = 0.5f', 'chromaticAberration = true')

$oldIs = @'
                        .innerShadow(shape = pillShape) {
                            InnerShadow(
                                radius = 8.dp * dampedDragAnimation.pressProgress,
                                color = Color.Black.copy(alpha = 0.15f),
                                alpha = dampedDragAnimation.pressProgress,
                            )
                        }
'@
$c = $c.Replace($oldIs, '')

[System.IO.File]::WriteAllText($f, $c, $enc)
"REWRITTEN"
$left = Select-String -Path $f -Pattern 'miuix|BloomStroke|LightSource|LightPosition|innerShadow|rememberDeviceTilt|gravityX|gravityY'
if ($left) { $left | ForEach-Object { "LEFT: $($_.Line.Trim())" } } else { "CLEAN: no leftover refs" }
