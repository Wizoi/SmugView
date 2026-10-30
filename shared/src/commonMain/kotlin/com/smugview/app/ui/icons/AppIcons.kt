package com.smugview.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The Material icons the screens use, as plain vectors (Apache-2.0, Google's Material icons, copied as they are): `material-icons-extended`
 * is not published for Compose Multiplatform. Generated once from the Android library; `XxxMirrored` icons flip in right-to-left layouts.
 */
object AppIcons {

    val ArrowBackMirrored: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.ArrowBack", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val ExitToAppMirrored: ImageVector by lazy {
        ImageVector.Builder(
            name = "AutoMirrored.Filled.ExitToApp", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = true
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.09f, 15.59f)
                lineTo(11.5f, 17f)
                lineToRelative(5f, -5f)
                lineToRelative(-5f, -5f)
                lineToRelative(-1.41f, 1.41f)
                lineTo(12.67f, 11f)
                horizontalLineTo(3f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(9.67f)
                lineToRelative(-2.58f, 2.59f)
                close()
                moveTo(19f, 3f)
                horizontalLineTo(5f)
                curveToRelative(-1.11f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(4f)
                horizontalLineToRelative(2f)
                verticalLineTo(5f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(14f)
                horizontalLineTo(5f)
                verticalLineToRelative(-4f)
                horizontalLineTo(3f)
                verticalLineToRelative(4f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
            }
        }.build()
    }

    val Add: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Add", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 13f)
                horizontalLineToRelative(-6f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineTo(5f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(6f)
                verticalLineTo(5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val ArrowBack: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.ArrowBack", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 11f)
                horizontalLineTo(7.83f)
                lineToRelative(5.59f, -5.59f)
                lineTo(12f, 4f)
                lineToRelative(-8f, 8f)
                lineToRelative(8f, 8f)
                lineToRelative(1.41f, -1.41f)
                lineTo(7.83f, 13f)
                horizontalLineTo(20f)
                verticalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val BookmarkBorder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.BookmarkBorder", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 3f)
                lineTo(7f, 3f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(5f, 21f)
                lineToRelative(7f, -3f)
                lineToRelative(7f, 3f)
                lineTo(19f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(17f, 18f)
                lineToRelative(-5f, -2.18f)
                lineTo(7f, 18f)
                lineTo(7f, 5f)
                horizontalLineToRelative(10f)
                verticalLineToRelative(13f)
                close()
            }
        }.build()
    }

    val CameraAlt: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CameraAlt", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 12f)
                moveToRelative(-3.2f, 0f)
                arcToRelative(3.2f, 3.2f, 0f, true, true, 6.4f, 0f)
                arcToRelative(3.2f, 3.2f, 0f, true, true, -6.4f, 0f)
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 2f)
                lineTo(7.17f, 4f)
                lineTo(4f, 4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(22f, 6f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-3.17f)
                lineTo(15f, 2f)
                lineTo(9f, 2f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-2.76f, 0f, -5f, -2.24f, -5f, -5f)
                reflectiveCurveToRelative(2.24f, -5f, 5f, -5f)
                reflectiveCurveToRelative(5f, 2.24f, 5f, 5f)
                reflectiveCurveToRelative(-2.24f, 5f, -5f, 5f)
                close()
            }
        }.build()
    }

    val Cancel: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Cancel", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.47f, 2f, 2f, 6.47f, 2f, 12f)
                reflectiveCurveToRelative(4.47f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.47f, 10f, -10f)
                reflectiveCurveTo(17.53f, 2f, 12f, 2f)
                close()
                moveTo(17f, 15.59f)
                lineTo(15.59f, 17f)
                lineTo(12f, 13.41f)
                lineTo(8.41f, 17f)
                lineTo(7f, 15.59f)
                lineTo(10.59f, 12f)
                lineTo(7f, 8.41f)
                lineTo(8.41f, 7f)
                lineTo(12f, 10.59f)
                lineTo(15.59f, 7f)
                lineTo(17f, 8.41f)
                lineTo(13.41f, 12f)
                lineTo(17f, 15.59f)
                close()
            }
        }.build()
    }

    val Cast: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Cast", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 3f)
                lineTo(3f, 3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(2f)
                lineTo(3f, 5f)
                horizontalLineToRelative(18f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(-7f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(7f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(23f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(1f, 18f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(3f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                close()
                moveTo(1f, 14f)
                verticalLineToRelative(2f)
                curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -3.87f, -3.13f, -7f, -7f, -7f)
                close()
                moveTo(1f, 10f)
                verticalLineToRelative(2f)
                curveToRelative(4.97f, 0f, 9f, 4.03f, 9f, 9f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -6.08f, -4.93f, -11f, -11f, -11f)
                close()
            }
        }.build()
    }

    val CastConnected: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CastConnected", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(1f, 18f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(3f)
                curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
                close()
                moveTo(1f, 14f)
                verticalLineToRelative(2f)
                curveToRelative(2.76f, 0f, 5f, 2.24f, 5f, 5f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -3.87f, -3.13f, -7f, -7f, -7f)
                close()
                moveTo(19f, 7f)
                lineTo(5f, 7f)
                verticalLineToRelative(1.63f)
                curveToRelative(3.96f, 1.28f, 7.09f, 4.41f, 8.37f, 8.37f)
                lineTo(19f, 17f)
                lineTo(19f, 7f)
                close()
                moveTo(1f, 10f)
                verticalLineToRelative(2f)
                curveToRelative(4.97f, 0f, 9f, 4.03f, 9f, 9f)
                horizontalLineToRelative(2f)
                curveToRelative(0f, -6.08f, -4.93f, -11f, -11f, -11f)
                close()
                moveTo(21f, 3f)
                lineTo(3f, 3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(2f)
                lineTo(3f, 5f)
                horizontalLineToRelative(18f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(-7f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(7f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(23f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
            }
        }.build()
    }

    val Check: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Check", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(9f, 16.17f)
                lineTo(4.83f, 12f)
                lineToRelative(-1.42f, 1.41f)
                lineTo(9f, 19f)
                lineTo(21f, 7f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }.build()
    }

    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.CheckCircle", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(10f, 17f)
                lineToRelative(-5f, -5f)
                lineToRelative(1.41f, -1.41f)
                lineTo(10f, 14.17f)
                lineToRelative(7.59f, -7.59f)
                lineTo(19f, 8f)
                lineToRelative(-9f, 9f)
                close()
            }
        }.build()
    }

    val ChevronRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.ChevronRight", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 6f)
                lineTo(8.59f, 7.41f)
                lineTo(13.17f, 12f)
                lineToRelative(-4.58f, 4.59f)
                lineTo(10f, 18f)
                lineToRelative(6f, -6f)
                close()
            }
        }.build()
    }

    val Close: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Close", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 6.41f)
                lineTo(17.59f, 5f)
                lineTo(12f, 10.59f)
                lineTo(6.41f, 5f)
                lineTo(5f, 6.41f)
                lineTo(10.59f, 12f)
                lineTo(5f, 17.59f)
                lineTo(6.41f, 19f)
                lineTo(12f, 13.41f)
                lineTo(17.59f, 19f)
                lineTo(19f, 17.59f)
                lineTo(13.41f, 12f)
                close()
            }
        }.build()
    }

    val ContentCopy: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.ContentCopy", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16f, 1f)
                lineTo(4f, 1f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(2f)
                lineTo(4f, 3f)
                horizontalLineToRelative(12f)
                lineTo(16f, 1f)
                close()
                moveTo(19f, 5f)
                lineTo(8f, 5f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(11f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(21f, 7f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(19f, 21f)
                lineTo(8f, 21f)
                lineTo(8f, 7f)
                horizontalLineToRelative(11f)
                verticalLineToRelative(14f)
                close()
            }
        }.build()
    }

    val DataUsage: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.DataUsage", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 2.05f)
                verticalLineToRelative(3.03f)
                curveToRelative(3.39f, 0.49f, 6f, 3.39f, 6f, 6.92f)
                curveToRelative(0f, 0.9f, -0.18f, 1.75f, -0.48f, 2.54f)
                lineToRelative(2.6f, 1.53f)
                curveToRelative(0.56f, -1.24f, 0.88f, -2.62f, 0.88f, -4.07f)
                curveToRelative(0f, -5.18f, -3.95f, -9.45f, -9f, -9.95f)
                close()
                moveTo(12f, 19f)
                curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
                curveToRelative(0f, -3.53f, 2.61f, -6.43f, 6f, -6.92f)
                verticalLineTo(2.05f)
                curveToRelative(-5.06f, 0.5f, -9f, 4.76f, -9f, 9.95f)
                curveToRelative(0f, 5.52f, 4.47f, 10f, 9.99f, 10f)
                curveToRelative(3.31f, 0f, 6.24f, -1.61f, 8.06f, -4.09f)
                lineToRelative(-2.6f, -1.53f)
                curveTo(16.17f, 17.98f, 14.21f, 19f, 12f, 19f)
                close()
            }
        }.build()
    }

    val Delete: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Delete", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(8f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(7f)
                horizontalLineTo(6f)
                verticalLineToRelative(12f)
                close()
                moveTo(19f, 4f)
                horizontalLineToRelative(-3.5f)
                lineToRelative(-1f, -1f)
                horizontalLineToRelative(-5f)
                lineToRelative(-1f, 1f)
                horizontalLineTo(5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(14f)
                verticalLineTo(4f)
                close()
            }
        }.build()
    }

    val DeveloperBoard: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.DeveloperBoard", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(22f, 9f)
                lineTo(22f, 7f)
                horizontalLineToRelative(-2f)
                lineTo(20f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                lineTo(4f, 3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-2f)
                lineTo(20f, 9f)
                horizontalLineToRelative(2f)
                close()
                moveTo(18f, 19f)
                lineTo(4f, 19f)
                lineTo(4f, 5f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(14f)
                close()
                moveTo(6f, 13f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(4f)
                lineTo(6f, 17f)
                close()
                moveTo(12f, 7f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(3f)
                horizontalLineToRelative(-4f)
                close()
                moveTo(6f, 7f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(5f)
                lineTo(6f, 12f)
                close()
                moveTo(12f, 11f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(-4f)
                close()
            }
        }.build()
    }

    val Download: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Download", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(5f, 20f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(-2f)
                horizontalLineTo(5f)
                verticalLineTo(20f)
                close()
                moveTo(19f, 9f)
                horizontalLineToRelative(-4f)
                verticalLineTo(3f)
                horizontalLineTo(9f)
                verticalLineToRelative(6f)
                horizontalLineTo(5f)
                lineToRelative(7f, 7f)
                lineTo(19f, 9f)
                close()
            }
        }.build()
    }

    val Edit: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Edit", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 17.25f)
                verticalLineTo(21f)
                horizontalLineToRelative(3.75f)
                lineTo(17.81f, 9.94f)
                lineToRelative(-3.75f, -3.75f)
                lineTo(3f, 17.25f)
                close()
                moveTo(20.71f, 7.04f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.02f, 0f, -1.41f)
                lineToRelative(-2.34f, -2.34f)
                curveToRelative(-0.39f, -0.39f, -1.02f, -0.39f, -1.41f, 0f)
                lineToRelative(-1.83f, 1.83f)
                lineToRelative(3.75f, 3.75f)
                lineToRelative(1.83f, -1.83f)
                close()
            }
        }.build()
    }

    val Event: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Event", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17f, 12f)
                horizontalLineToRelative(-5f)
                verticalLineToRelative(5f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-5f)
                close()
                moveTo(16f, 1f)
                verticalLineToRelative(2f)
                lineTo(8f, 3f)
                lineTo(8f, 1f)
                lineTo(6f, 1f)
                verticalLineToRelative(2f)
                lineTo(5f, 3f)
                curveToRelative(-1.11f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(3f, 19f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(21f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-1f)
                lineTo(18f, 1f)
                horizontalLineToRelative(-2f)
                close()
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(5f, 8f)
                horizontalLineToRelative(14f)
                verticalLineToRelative(11f)
                close()
            }
        }.build()
    }

    val FastForward: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FastForward", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(4f, 18f)
                lineToRelative(8.5f, -6f)
                lineTo(4f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(13f, 6f)
                verticalLineToRelative(12f)
                lineToRelative(8.5f, -6f)
                lineTo(13f, 6f)
                close()
            }
        }.build()
    }

    val FastRewind: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FastRewind", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 18f)
                lineTo(11f, 6f)
                lineToRelative(-8.5f, 6f)
                lineToRelative(8.5f, 6f)
                close()
                moveTo(11.5f, 12f)
                lineToRelative(8.5f, 6f)
                lineTo(20f, 6f)
                lineToRelative(-8.5f, 6f)
                close()
            }
        }.build()
    }

    val FilterList: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FilterList", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 18f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(-4f)
                verticalLineToRelative(2f)
                close()
                moveTo(3f, 6f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(18f)
                lineTo(21f, 6f)
                lineTo(3f, 6f)
                close()
                moveTo(6f, 13f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(-2f)
                lineTo(6f, 11f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val Folder: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Folder", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 4f)
                horizontalLineTo(4f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(2f, 18f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(8f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineToRelative(-8f)
                lineToRelative(-2f, -2f)
                close()
            }
        }.build()
    }

    val FolderOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.FolderOpen", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20f, 6f)
                horizontalLineToRelative(-8f)
                lineToRelative(-2f, -2f)
                lineTo(4f, 4f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(2f, 18f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(16f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(22f, 8f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(20f, 18f)
                lineTo(4f, 18f)
                lineTo(4f, 8f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(10f)
                close()
            }
        }.build()
    }

    val History: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.History", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(13f, 3f)
                curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                lineTo(1f, 12f)
                lineToRelative(3.89f, 3.89f)
                lineToRelative(0.07f, 0.14f)
                lineTo(9f, 12f)
                lineTo(6f, 12f)
                curveToRelative(0f, -3.87f, 3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                curveToRelative(-1.93f, 0f, -3.68f, -0.79f, -4.94f, -2.06f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(8.27f, 19.99f, 10.51f, 21f, 13f, 21f)
                curveToRelative(4.97f, 0f, 9f, -4.03f, 9f, -9f)
                reflectiveCurveToRelative(-4.03f, -9f, -9f, -9f)
                close()
                moveTo(12f, 8f)
                verticalLineToRelative(5f)
                lineToRelative(4.28f, 2.54f)
                lineToRelative(0.72f, -1.21f)
                lineToRelative(-3.5f, -2.08f)
                lineTo(13.5f, 8f)
                lineTo(12f, 8f)
                close()
            }
        }.build()
    }

    val Home: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Home", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(10f, 20f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(4f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(-8f)
                horizontalLineToRelative(3f)
                lineTo(12f, 3f)
                lineTo(2f, 12f)
                horizontalLineToRelative(3f)
                verticalLineToRelative(8f)
                close()
            }
        }.build()
    }

    val Hub: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Hub", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.4f, 18.2f)
                curveTo(8.78f, 18.7f, 9f, 19.32f, 9f, 20f)
                curveToRelative(0f, 1.66f, -1.34f, 3f, -3f, 3f)
                reflectiveCurveToRelative(-3f, -1.34f, -3f, -3f)
                reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
                curveToRelative(0.44f, 0f, 0.85f, 0.09f, 1.23f, 0.26f)
                lineToRelative(1.41f, -1.77f)
                curveToRelative(-0.92f, -1.03f, -1.29f, -2.39f, -1.09f, -3.69f)
                lineToRelative(-2.03f, -0.68f)
                curveTo(4.98f, 11.95f, 4.06f, 12.5f, 3f, 12.5f)
                curveToRelative(-1.66f, 0f, -3f, -1.34f, -3f, -3f)
                reflectiveCurveToRelative(1.34f, -3f, 3f, -3f)
                reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
                curveToRelative(0f, 0.07f, 0f, 0.14f, -0.01f, 0.21f)
                lineToRelative(2.03f, 0.68f)
                curveToRelative(0.64f, -1.21f, 1.82f, -2.09f, 3.22f, -2.32f)
                lineToRelative(0f, -2.16f)
                curveTo(9.96f, 5.57f, 9f, 4.4f, 9f, 3f)
                curveToRelative(0f, -1.66f, 1.34f, -3f, 3f, -3f)
                reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
                curveToRelative(0f, 1.4f, -0.96f, 2.57f, -2.25f, 2.91f)
                verticalLineToRelative(2.16f)
                curveToRelative(1.4f, 0.23f, 2.58f, 1.11f, 3.22f, 2.32f)
                lineToRelative(2.03f, -0.68f)
                curveTo(18f, 9.64f, 18f, 9.57f, 18f, 9.5f)
                curveToRelative(0f, -1.66f, 1.34f, -3f, 3f, -3f)
                reflectiveCurveToRelative(3f, 1.34f, 3f, 3f)
                reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f)
                curveToRelative(-1.06f, 0f, -1.98f, -0.55f, -2.52f, -1.37f)
                lineToRelative(-2.03f, 0.68f)
                curveToRelative(0.2f, 1.29f, -0.16f, 2.65f, -1.09f, 3.69f)
                lineToRelative(1.41f, 1.77f)
                curveTo(17.15f, 17.09f, 17.56f, 17f, 18f, 17f)
                curveToRelative(1.66f, 0f, 3f, 1.34f, 3f, 3f)
                reflectiveCurveToRelative(-1.34f, 3f, -3f, 3f)
                reflectiveCurveToRelative(-3f, -1.34f, -3f, -3f)
                curveToRelative(0f, -0.68f, 0.22f, -1.3f, 0.6f, -1.8f)
                lineToRelative(-1.41f, -1.77f)
                curveToRelative(-1.35f, 0.75f, -3.01f, 0.76f, -4.37f, 0f)
                lineTo(8.4f, 18.2f)
                close()
            }
        }.build()
    }

    val Image: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Image", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 19f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineTo(5f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                close()
                moveTo(8.5f, 13.5f)
                lineToRelative(2.5f, 3.01f)
                lineTo(14.5f, 12f)
                lineToRelative(4.5f, 6f)
                horizontalLineTo(5f)
                lineToRelative(3.5f, -4.5f)
                close()
            }
        }.build()
    }

    val Info: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Info", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
                moveTo(13f, 17f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(-6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(6f)
                close()
                moveTo(13f, 9f)
                horizontalLineToRelative(-2f)
                lineTo(11f, 7f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val InsertDriveFile: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.InsertDriveFile", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 2f)
                curveToRelative(-1.1f, 0f, -1.99f, 0.9f, -1.99f, 2f)
                lineTo(4f, 20f)
                curveToRelative(0f, 1.1f, 0.89f, 2f, 1.99f, 2f)
                lineTo(18f, 22f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(20f, 8f)
                lineToRelative(-6f, -6f)
                lineTo(6f, 2f)
                close()
                moveTo(13f, 9f)
                lineTo(13f, 3.5f)
                lineTo(18.5f, 9f)
                lineTo(13f, 9f)
                close()
            }
        }.build()
    }

    val Iso: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Iso", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19f, 3f)
                lineTo(5f, 3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(21f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(5.5f, 7.5f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(-2f)
                lineTo(9f, 5.5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                lineTo(11f, 9f)
                lineTo(9f, 9f)
                verticalLineToRelative(2f)
                lineTo(7.5f, 11f)
                lineTo(7.5f, 9f)
                horizontalLineToRelative(-2f)
                lineTo(5.5f, 7.5f)
                close()
                moveTo(19f, 19f)
                lineTo(5f, 19f)
                lineTo(19f, 5f)
                verticalLineToRelative(14f)
                close()
                moveTo(17f, 17f)
                verticalLineToRelative(-1.5f)
                horizontalLineToRelative(-5f)
                lineTo(12f, 17f)
                horizontalLineToRelative(5f)
                close()
            }
        }.build()
    }

    val KeyboardArrowDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.KeyboardArrowDown", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.41f, 8.59f)
                lineTo(12f, 13.17f)
                lineToRelative(4.59f, -4.58f)
                lineTo(18f, 10f)
                lineToRelative(-6f, 6f)
                lineToRelative(-6f, -6f)
                lineToRelative(1.41f, -1.41f)
                close()
            }
        }.build()
    }

    val KeyboardArrowRight: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.KeyboardArrowRight", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8.59f, 16.59f)
                lineTo(13.17f, 12f)
                lineTo(8.59f, 7.41f)
                lineTo(10f, 6f)
                lineToRelative(6f, 6f)
                lineToRelative(-6f, 6f)
                lineToRelative(-1.41f, -1.41f)
                close()
            }
        }.build()
    }

    val Lens: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Lens", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                reflectiveCurveToRelative(4.48f, 10f, 10f, 10f)
                reflectiveCurveToRelative(10f, -4.48f, 10f, -10f)
                reflectiveCurveTo(17.52f, 2f, 12f, 2f)
                close()
            }
        }.build()
    }

    val LightMode: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LightMode", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 7f)
                curveToRelative(-2.76f, 0f, -5f, 2.24f, -5f, 5f)
                reflectiveCurveToRelative(2.24f, 5f, 5f, 5f)
                reflectiveCurveToRelative(5f, -2.24f, 5f, -5f)
                reflectiveCurveTo(14.76f, 7f, 12f, 7f)
                lineTo(12f, 7f)
                close()
                moveTo(2f, 13f)
                lineToRelative(2f, 0f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                lineToRelative(-2f, 0f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                reflectiveCurveTo(1.45f, 13f, 2f, 13f)
                close()
                moveTo(20f, 13f)
                lineToRelative(2f, 0f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                reflectiveCurveToRelative(-0.45f, -1f, -1f, -1f)
                lineToRelative(-2f, 0f)
                curveToRelative(-0.55f, 0f, -1f, 0.45f, -1f, 1f)
                reflectiveCurveTo(19.45f, 13f, 20f, 13f)
                close()
                moveTo(11f, 2f)
                verticalLineToRelative(2f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                verticalLineTo(2f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                reflectiveCurveTo(11f, 1.45f, 11f, 2f)
                close()
                moveTo(11f, 20f)
                verticalLineToRelative(2f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                reflectiveCurveToRelative(1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-2f)
                curveToRelative(0f, -0.55f, -0.45f, -1f, -1f, -1f)
                curveTo(11.45f, 19f, 11f, 19.45f, 11f, 20f)
                close()
                moveTo(5.99f, 4.58f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0f)
                reflectiveCurveToRelative(0.39f, -1.03f, 0f, -1.41f)
                lineTo(5.99f, 4.58f)
                close()
                moveTo(18.36f, 16.95f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                curveToRelative(0.39f, 0.39f, 1.03f, 0.39f, 1.41f, 0f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                lineTo(18.36f, 16.95f)
                close()
                moveTo(19.42f, 5.99f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0f)
                lineTo(19.42f, 5.99f)
                close()
                moveTo(7.05f, 18.36f)
                curveToRelative(0.39f, -0.39f, 0.39f, -1.03f, 0f, -1.41f)
                curveToRelative(-0.39f, -0.39f, -1.03f, -0.39f, -1.41f, 0f)
                lineToRelative(-1.06f, 1.06f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.03f, 0f, 1.41f)
                reflectiveCurveToRelative(1.03f, 0.39f, 1.41f, 0f)
                lineTo(7.05f, 18.36f)
                close()
            }
        }.build()
    }

    val LocalOffer: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LocalOffer", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21.41f, 11.58f)
                lineToRelative(-9f, -9f)
                curveTo(12.05f, 2.22f, 11.55f, 2f, 11f, 2f)
                horizontalLineTo(4f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(7f)
                curveToRelative(0f, 0.55f, 0.22f, 1.05f, 0.59f, 1.42f)
                lineToRelative(9f, 9f)
                curveToRelative(0.36f, 0.36f, 0.86f, 0.58f, 1.41f, 0.58f)
                curveToRelative(0.55f, 0f, 1.05f, -0.22f, 1.41f, -0.59f)
                lineToRelative(7f, -7f)
                curveToRelative(0.37f, -0.36f, 0.59f, -0.86f, 0.59f, -1.41f)
                curveToRelative(0f, -0.55f, -0.23f, -1.06f, -0.59f, -1.42f)
                close()
                moveTo(5.5f, 7f)
                curveTo(4.67f, 7f, 4f, 6.33f, 4f, 5.5f)
                reflectiveCurveTo(4.67f, 4f, 5.5f, 4f)
                reflectiveCurveTo(7f, 4.67f, 7f, 5.5f)
                reflectiveCurveTo(6.33f, 7f, 5.5f, 7f)
                close()
            }
        }.build()
    }

    val Lock: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Lock", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 8f)
                horizontalLineToRelative(-1f)
                lineTo(17f, 6f)
                curveToRelative(0f, -2.76f, -2.24f, -5f, -5f, -5f)
                reflectiveCurveTo(7f, 3.24f, 7f, 6f)
                verticalLineToRelative(2f)
                lineTo(6f, 8f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(20f, 10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(12f, 17f)
                curveToRelative(-1.1f, 0f, -2f, -0.9f, -2f, -2f)
                reflectiveCurveToRelative(0.9f, -2f, 2f, -2f)
                reflectiveCurveToRelative(2f, 0.9f, 2f, 2f)
                reflectiveCurveToRelative(-0.9f, 2f, -2f, 2f)
                close()
                moveTo(15.1f, 8f)
                lineTo(8.9f, 8f)
                lineTo(8.9f, 6f)
                curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val LockOpen: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.LockOpen", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 17f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                reflectiveCurveToRelative(-0.9f, -2f, -2f, -2f)
                reflectiveCurveToRelative(-2f, 0.9f, -2f, 2f)
                reflectiveCurveToRelative(0.9f, 2f, 2f, 2f)
                close()
                moveTo(18f, 8f)
                horizontalLineToRelative(-1f)
                lineTo(17f, 6f)
                curveToRelative(0f, -2.76f, -2.24f, -5f, -5f, -5f)
                reflectiveCurveTo(7f, 3.24f, 7f, 6f)
                horizontalLineToRelative(1.9f)
                curveToRelative(0f, -1.71f, 1.39f, -3.1f, 3.1f, -3.1f)
                curveToRelative(1.71f, 0f, 3.1f, 1.39f, 3.1f, 3.1f)
                verticalLineToRelative(2f)
                lineTo(6f, 8f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(10f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                lineTo(20f, 10f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(18f, 20f)
                lineTo(6f, 20f)
                lineTo(6f, 10f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(10f)
                close()
            }
        }.build()
    }

    val NotificationsActive: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.NotificationsActive", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.58f, 4.08f)
                lineTo(6.15f, 2.65f)
                curveTo(3.75f, 4.48f, 2.17f, 7.3f, 2.03f, 10.5f)
                horizontalLineToRelative(2f)
                curveToRelative(0.15f, -2.65f, 1.51f, -4.97f, 3.55f, -6.42f)
                close()
                moveTo(19.97f, 10.5f)
                horizontalLineToRelative(2f)
                curveToRelative(-0.15f, -3.2f, -1.73f, -6.02f, -4.12f, -7.85f)
                lineToRelative(-1.42f, 1.43f)
                curveToRelative(2.02f, 1.45f, 3.39f, 3.77f, 3.54f, 6.42f)
                close()
                moveTo(18f, 11f)
                curveToRelative(0f, -3.07f, -1.64f, -5.64f, -4.5f, -6.32f)
                lineTo(13.5f, 4f)
                curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(0.68f)
                curveTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f)
                verticalLineToRelative(5f)
                lineToRelative(-2f, 2f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-1f)
                lineToRelative(-2f, -2f)
                verticalLineToRelative(-5f)
                close()
                moveTo(12f, 22f)
                curveToRelative(0.14f, 0f, 0.27f, -0.01f, 0.4f, -0.04f)
                curveToRelative(0.65f, -0.14f, 1.18f, -0.58f, 1.44f, -1.18f)
                curveToRelative(0.1f, -0.24f, 0.15f, -0.5f, 0.15f, -0.78f)
                horizontalLineToRelative(-4f)
                curveToRelative(0.01f, 1.1f, 0.9f, 2f, 2.01f, 2f)
                close()
            }
        }.build()
    }

    val NotificationsNone: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.NotificationsNone", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 22f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                horizontalLineToRelative(-4f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                close()
                moveTo(18f, 16f)
                verticalLineToRelative(-5f)
                curveToRelative(0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
                lineTo(13.5f, 4f)
                curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
                reflectiveCurveToRelative(-1.5f, 0.67f, -1.5f, 1.5f)
                verticalLineToRelative(0.68f)
                curveTo(7.64f, 5.36f, 6f, 7.92f, 6f, 11f)
                verticalLineToRelative(5f)
                lineToRelative(-2f, 2f)
                verticalLineToRelative(1f)
                horizontalLineToRelative(16f)
                verticalLineToRelative(-1f)
                lineToRelative(-2f, -2f)
                close()
                moveTo(16f, 17f)
                lineTo(8f, 17f)
                verticalLineToRelative(-6f)
                curveToRelative(0f, -2.48f, 1.51f, -4.5f, 4f, -4.5f)
                reflectiveCurveToRelative(4f, 2.02f, 4f, 4.5f)
                verticalLineToRelative(6f)
                close()
            }
        }.build()
    }

    val Pause: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Pause", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 19f)
                horizontalLineToRelative(4f)
                lineTo(10f, 5f)
                lineTo(6f, 5f)
                verticalLineToRelative(14f)
                close()
                moveTo(14f, 5f)
                verticalLineToRelative(14f)
                horizontalLineToRelative(4f)
                lineTo(18f, 5f)
                horizontalLineToRelative(-4f)
                close()
            }
        }.build()
    }

    val Photo: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Photo", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 19f)
                verticalLineTo(5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                horizontalLineTo(5f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(14f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(14f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                close()
                moveTo(8.5f, 13.5f)
                lineToRelative(2.5f, 3.01f)
                lineTo(14.5f, 12f)
                lineToRelative(4.5f, 6f)
                horizontalLineTo(5f)
                lineToRelative(3.5f, -4.5f)
                close()
            }
        }.build()
    }

    val PhotoAlbum: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.PhotoAlbum", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 2f)
                horizontalLineTo(6f)
                curveTo(4.9f, 2f, 4f, 2.9f, 4f, 4f)
                verticalLineToRelative(16f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(12f)
                curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
                verticalLineTo(4f)
                curveTo(20f, 2.9f, 19.1f, 2f, 18f, 2f)
                close()
                moveTo(11f, 4f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(7f)
                lineToRelative(-2.5f, -1.5f)
                lineTo(11f, 11f)
                verticalLineTo(4f)
                close()
                moveTo(7f, 18f)
                lineToRelative(2.38f, -3.17f)
                lineTo(11f, 17f)
                lineToRelative(2.62f, -3.5f)
                lineTo(17f, 18f)
                horizontalLineTo(7f)
                close()
            }
        }.build()
    }

    val PlayArrow: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.PlayArrow", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(8f, 5f)
                verticalLineToRelative(14f)
                lineToRelative(11f, -7f)
                close()
            }
        }.build()
    }

    val Refresh: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Refresh", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(17.65f, 6.35f)
                curveTo(16.2f, 4.9f, 14.21f, 4f, 12f, 4f)
                curveToRelative(-4.42f, 0f, -7.99f, 3.58f, -7.99f, 8f)
                reflectiveCurveToRelative(3.57f, 8f, 7.99f, 8f)
                curveToRelative(3.73f, 0f, 6.84f, -2.55f, 7.73f, -6f)
                horizontalLineToRelative(-2.08f)
                curveToRelative(-0.82f, 2.33f, -3.04f, 4f, -5.65f, 4f)
                curveToRelative(-3.31f, 0f, -6f, -2.69f, -6f, -6f)
                reflectiveCurveToRelative(2.69f, -6f, 6f, -6f)
                curveToRelative(1.66f, 0f, 3.14f, 0.69f, 4.22f, 1.78f)
                lineTo(13f, 11f)
                horizontalLineToRelative(7f)
                verticalLineTo(4f)
                lineToRelative(-2.35f, 2.35f)
                close()
            }
        }.build()
    }

    val Search: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Search", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.5f, 14f)
                horizontalLineToRelative(-0.79f)
                lineToRelative(-0.28f, -0.27f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
                reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
                curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
                lineToRelative(0.27f, 0.28f)
                verticalLineToRelative(0.79f)
                lineToRelative(5f, 4.99f)
                lineTo(20.49f, 19f)
                lineToRelative(-4.99f, -5f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
                reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
                reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
                close()
            }
        }.build()
    }

    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Settings", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                lineToRelative(2.03f, -1.58f)
                curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                lineToRelative(-1.92f, -3.32f)
                curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                lineToRelative(-2.39f, 0.96f)
                curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                lineTo(14.4f, 2.81f)
                curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                horizontalLineToRelative(-3.84f)
                curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
                lineTo(9.25f, 5.35f)
                curveTo(8.66f, 5.59f, 8.12f, 5.92f, 7.63f, 6.29f)
                lineTo(5.24f, 5.33f)
                curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
                lineTo(2.74f, 8.87f)
                curveTo(2.62f, 9.08f, 2.66f, 9.34f, 2.86f, 9.48f)
                lineToRelative(2.03f, 1.58f)
                curveTo(4.84f, 11.36f, 4.8f, 11.69f, 4.8f, 12f)
                reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                lineToRelative(-2.03f, 1.58f)
                curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f)
                curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f)
                curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f)
                curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f)
                curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f)
                curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f)
                curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f)
                curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineTo(19.14f, 12.94f)
                close()
                moveTo(12f, 15.6f)
                curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveTo(13.98f, 15.6f, 12f, 15.6f)
                close()
            }
        }.build()
    }

    val Share: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Share", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(18f, 16.08f)
                curveToRelative(-0.76f, 0f, -1.44f, 0.3f, -1.96f, 0.77f)
                lineTo(8.91f, 12.7f)
                curveToRelative(0.05f, -0.23f, 0.09f, -0.46f, 0.09f, -0.7f)
                reflectiveCurveToRelative(-0.04f, -0.47f, -0.09f, -0.7f)
                lineToRelative(7.05f, -4.11f)
                curveToRelative(0.54f, 0.5f, 1.25f, 0.81f, 2.04f, 0.81f)
                curveToRelative(1.66f, 0f, 3f, -1.34f, 3f, -3f)
                reflectiveCurveToRelative(-1.34f, -3f, -3f, -3f)
                reflectiveCurveToRelative(-3f, 1.34f, -3f, 3f)
                curveToRelative(0f, 0.24f, 0.04f, 0.47f, 0.09f, 0.7f)
                lineTo(8.04f, 9.81f)
                curveTo(7.5f, 9.31f, 6.79f, 9f, 6f, 9f)
                curveToRelative(-1.66f, 0f, -3f, 1.34f, -3f, 3f)
                reflectiveCurveToRelative(1.34f, 3f, 3f, 3f)
                curveToRelative(0.79f, 0f, 1.5f, -0.31f, 2.04f, -0.81f)
                lineToRelative(7.12f, 4.16f)
                curveToRelative(-0.05f, 0.21f, -0.08f, 0.43f, -0.08f, 0.65f)
                curveToRelative(0f, 1.61f, 1.31f, 2.92f, 2.92f, 2.92f)
                curveToRelative(1.61f, 0f, 2.92f, -1.31f, 2.92f, -2.92f)
                reflectiveCurveToRelative(-1.31f, -2.92f, -2.92f, -2.92f)
                close()
            }
        }.build()
    }

    val SkipNext: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SkipNext", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 18f)
                lineToRelative(8.5f, -6f)
                lineTo(6f, 6f)
                verticalLineToRelative(12f)
                close()
                moveTo(16f, 6f)
                verticalLineToRelative(12f)
                horizontalLineToRelative(2f)
                verticalLineTo(6f)
                horizontalLineToRelative(-2f)
                close()
            }
        }.build()
    }

    val SkipPrevious: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SkipPrevious", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(6f, 6f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(12f)
                lineTo(6f, 18f)
                close()
                moveTo(9.5f, 12f)
                lineToRelative(8.5f, 6f)
                lineTo(18f, 6f)
                close()
            }
        }.build()
    }

    val Sort: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Sort", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 18f)
                horizontalLineToRelative(6f)
                verticalLineToRelative(-2f)
                lineTo(3f, 16f)
                verticalLineToRelative(2f)
                close()
                moveTo(3f, 6f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(18f)
                lineTo(21f, 6f)
                lineTo(3f, 6f)
                close()
                moveTo(3f, 13f)
                horizontalLineToRelative(12f)
                verticalLineToRelative(-2f)
                lineTo(3f, 11f)
                verticalLineToRelative(2f)
                close()
            }
        }.build()
    }

    val SortByAlpha: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.SortByAlpha", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(14.94f, 4.66f)
                horizontalLineToRelative(-4.72f)
                lineToRelative(2.36f, -2.36f)
                close()
                moveTo(10.25f, 19.37f)
                horizontalLineToRelative(4.66f)
                lineToRelative(-2.33f, 2.33f)
                close()
                moveTo(6.1f, 6.27f)
                lineTo(1.6f, 17.73f)
                horizontalLineToRelative(1.84f)
                lineToRelative(0.92f, -2.45f)
                horizontalLineToRelative(5.11f)
                lineToRelative(0.92f, 2.45f)
                horizontalLineToRelative(1.84f)
                lineTo(7.74f, 6.27f)
                lineTo(6.1f, 6.27f)
                close()
                moveTo(4.97f, 13.64f)
                lineToRelative(1.94f, -5.18f)
                lineToRelative(1.94f, 5.18f)
                lineTo(4.97f, 13.64f)
                close()
                moveTo(15.73f, 16.14f)
                horizontalLineToRelative(6.12f)
                verticalLineToRelative(1.59f)
                horizontalLineToRelative(-8.53f)
                verticalLineToRelative(-1.29f)
                lineToRelative(5.92f, -8.56f)
                horizontalLineToRelative(-5.88f)
                verticalLineToRelative(-1.6f)
                horizontalLineToRelative(8.3f)
                verticalLineToRelative(1.26f)
                lineToRelative(-5.93f, 8.6f)
                close()
            }
        }.build()
    }

    val Speed: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Speed", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(20.38f, 8.57f)
                lineToRelative(-1.23f, 1.85f)
                arcToRelative(8f, 8f, 0f, false, true, -0.22f, 7.58f)
                lineTo(5.07f, 18f)
                arcTo(8f, 8f, 0f, false, true, 15.58f, 6.85f)
                lineToRelative(1.85f, -1.23f)
                arcTo(10f, 10f, 0f, false, false, 3.35f, 19f)
                arcToRelative(2f, 2f, 0f, false, false, 1.72f, 1f)
                horizontalLineToRelative(13.85f)
                arcToRelative(2f, 2f, 0f, false, false, 1.74f, -1f)
                arcToRelative(10f, 10f, 0f, false, false, -0.27f, -10.44f)
                close()
                moveTo(10.59f, 15.41f)
                arcToRelative(2f, 2f, 0f, false, false, 2.83f, 0f)
                lineToRelative(5.66f, -8.49f)
                lineToRelative(-8.49f, 5.66f)
                arcToRelative(2f, 2f, 0f, false, false, 0f, 2.83f)
                close()
            }
        }.build()
    }

    val Tv: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.Tv", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(21f, 3f)
                lineTo(3f, 3f)
                curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(5f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(8f)
                verticalLineToRelative(-2f)
                horizontalLineToRelative(5f)
                curveToRelative(1.1f, 0f, 1.99f, -0.9f, 1.99f, -2f)
                lineTo(23f, 5f)
                curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
                close()
                moveTo(21f, 17f)
                lineTo(3f, 17f)
                lineTo(3f, 5f)
                horizontalLineToRelative(18f)
                verticalLineToRelative(12f)
                close()
            }
        }.build()
    }

    val TvOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.TvOff", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(1f, 3.54f)
                lineToRelative(1.53f, 1.53f)
                curveTo(1.65f, 5.28f, 1f, 6.06f, 1f, 7f)
                verticalLineToRelative(12f)
                curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
                horizontalLineToRelative(15.46f)
                lineToRelative(2f, 2f)
                lineToRelative(1.26f, -1.27f)
                lineTo(2.27f, 2.27f)
                lineTo(1f, 3.54f)
                close()
                moveTo(3f, 19f)
                verticalLineTo(7f)
                horizontalLineToRelative(1.46f)
                lineToRelative(12f, 12f)
                horizontalLineTo(3f)
                close()
                moveTo(21f, 5f)
                horizontalLineToRelative(-7.58f)
                lineToRelative(3.29f, -3.3f)
                lineTo(16f, 1f)
                lineToRelative(-4f, 4f)
                lineToRelative(-4f, -4f)
                lineToRelative(-0.7f, 0.7f)
                lineTo(10.58f, 5f)
                horizontalLineTo(7.52f)
                lineToRelative(2f, 2f)
                horizontalLineTo(21f)
                verticalLineToRelative(11.48f)
                lineToRelative(1.65f, 1.65f)
                curveToRelative(0.22f, -0.32f, 0.35f, -0.71f, 0.35f, -1.13f)
                verticalLineTo(7f)
                curveToRelative(0f, -1.11f, -0.89f, -2f, -2f, -2f)
                close()
            }
        }.build()
    }

    val VolumeOff: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.VolumeOff", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(16.5f, 12f)
                curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(2.21f)
                lineToRelative(2.45f, 2.45f)
                curveToRelative(0.03f, -0.2f, 0.05f, -0.41f, 0.05f, -0.63f)
                close()
                moveTo(19f, 12f)
                curveToRelative(0f, 0.94f, -0.2f, 1.82f, -0.54f, 2.64f)
                lineToRelative(1.51f, 1.51f)
                curveTo(20.63f, 14.91f, 21f, 13.5f, 21f, 12f)
                curveToRelative(0f, -4.28f, -2.99f, -7.86f, -7f, -8.77f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
                close()
                moveTo(4.27f, 3f)
                lineTo(3f, 4.27f)
                lineTo(7.73f, 9f)
                lineTo(3f, 9f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(4f)
                lineToRelative(5f, 5f)
                verticalLineToRelative(-6.73f)
                lineToRelative(4.25f, 4.25f)
                curveToRelative(-0.67f, 0.52f, -1.42f, 0.93f, -2.25f, 1.18f)
                verticalLineToRelative(2.06f)
                curveToRelative(1.38f, -0.31f, 2.63f, -0.95f, 3.69f, -1.81f)
                lineTo(19.73f, 21f)
                lineTo(21f, 19.73f)
                lineToRelative(-9f, -9f)
                lineTo(4.27f, 3f)
                close()
                moveTo(12f, 4f)
                lineTo(9.91f, 6.09f)
                lineTo(12f, 8.18f)
                lineTo(12f, 4f)
                close()
            }
        }.build()
    }

    val VolumeUp: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.VolumeUp", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(3f, 9f)
                verticalLineToRelative(6f)
                horizontalLineToRelative(4f)
                lineToRelative(5f, 5f)
                lineTo(12f, 4f)
                lineTo(7f, 9f)
                lineTo(3f, 9f)
                close()
                moveTo(16.5f, 12f)
                curveToRelative(0f, -1.77f, -1.02f, -3.29f, -2.5f, -4.03f)
                verticalLineToRelative(8.05f)
                curveToRelative(1.48f, -0.73f, 2.5f, -2.25f, 2.5f, -4.02f)
                close()
                moveTo(14f, 3.23f)
                verticalLineToRelative(2.06f)
                curveToRelative(2.89f, 0.86f, 5f, 3.54f, 5f, 6.71f)
                reflectiveCurveToRelative(-2.11f, 5.85f, -5f, 6.71f)
                verticalLineToRelative(2.06f)
                curveToRelative(4.01f, -0.91f, 7f, -4.49f, 7f, -8.77f)
                reflectiveCurveToRelative(-2.99f, -7.86f, -7f, -8.77f)
                close()
            }
        }.build()
    }

    val ZoomIn: ImageVector by lazy {
        ImageVector.Builder(
            name = "Filled.ZoomIn", defaultWidth = 24f.dp, defaultHeight = 24f.dp,
            viewportWidth = 24f, viewportHeight = 24f, autoMirror = false
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(15.5f, 14f)
                horizontalLineToRelative(-0.79f)
                lineToRelative(-0.28f, -0.27f)
                curveTo(15.41f, 12.59f, 16f, 11.11f, 16f, 9.5f)
                curveTo(16f, 5.91f, 13.09f, 3f, 9.5f, 3f)
                reflectiveCurveTo(3f, 5.91f, 3f, 9.5f)
                reflectiveCurveTo(5.91f, 16f, 9.5f, 16f)
                curveToRelative(1.61f, 0f, 3.09f, -0.59f, 4.23f, -1.57f)
                lineToRelative(0.27f, 0.28f)
                verticalLineToRelative(0.79f)
                lineToRelative(5f, 4.99f)
                lineTo(20.49f, 19f)
                lineToRelative(-4.99f, -5f)
                close()
                moveTo(9.5f, 14f)
                curveTo(7.01f, 14f, 5f, 11.99f, 5f, 9.5f)
                reflectiveCurveTo(7.01f, 5f, 9.5f, 5f)
                reflectiveCurveTo(14f, 7.01f, 14f, 9.5f)
                reflectiveCurveTo(11.99f, 14f, 9.5f, 14f)
                close()
            }
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 10f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(2f)
                horizontalLineTo(9f)
                verticalLineToRelative(-2f)
                horizontalLineTo(7f)
                verticalLineTo(9f)
                horizontalLineToRelative(2f)
                verticalLineTo(7f)
                horizontalLineToRelative(1f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(2f)
                verticalLineToRelative(1f)
                close()
            }
        }.build()
    }

}
