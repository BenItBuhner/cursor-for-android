package com.cursorforandroid.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import com.cursorforandroid.ui.icons.ProjectIcons
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * A Project's icon as a bitmap RemoteViews can carry. The icons are [ImageVector]s built at run time (see
 * [ProjectIcons]), which no launcher can inflate, so each is drawn once in white — as the widget's resource glyphs
 * are, all of them tinted at render time the same way — at the pixel size its slot needs, and kept: RemoteViews ship
 * a bitmap once however many views show it, by identity, so the same icon at the same size must be the same object.
 */
internal object ProjectIconBitmaps {

    /** A 16dp slot at 4x. The icons are line art, and every bitmap rides the widget's one binder transaction. */
    const val MAX_PX = 64

    private val cache = ConcurrentHashMap<String, Bitmap>()

    /** The glyph for [icon] (the cube for none, or one this build cannot draw) at [sizeDp] on [context]'s screen. */
    fun bitmap(context: Context, icon: String?, sizeDp: Float): Bitmap {
        val px = (sizeDp * context.resources.displayMetrics.density).roundToInt().coerceIn(1, MAX_PX)
        val id = ProjectIcons.canonical(icon) ?: ProjectIcons.DEFAULT_ICON
        return cache.getOrPut("$id@$px") { render(ProjectIcons.vector(id), px) }
    }

    /** [vector] filling a [px]-square bitmap, in white at the vector's own alphas. */
    fun render(vector: ImageVector, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(px / vector.viewportWidth, px / vector.viewportHeight)
        draw(canvas, vector.root)
        return bitmap
    }

    /** A group as Compose's `GroupComponent` draws one: translated, rotated and scaled about its pivot, then clipped. */
    private fun draw(canvas: Canvas, group: VectorGroup) {
        canvas.save()
        canvas.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
        canvas.rotate(group.rotation)
        canvas.scale(group.scaleX, group.scaleY)
        canvas.translate(-group.pivotX, -group.pivotY)
        if (group.clipPathData.isNotEmpty()) canvas.clipPath(group.clipPathData.toAndroidPath(PathFillType.NonZero))
        for (node in group) {
            when (node) {
                is VectorGroup -> draw(canvas, node)
                is VectorPath -> draw(canvas, node)
            }
        }
        canvas.restore()
    }

    /** A path as `PathComponent` draws one: the fill, then the stroke over it. */
    private fun draw(canvas: Canvas, path: VectorPath) {
        val outline = path.pathData.toAndroidPath(path.pathFillType)
        path.fill?.let { brush ->
            canvas.drawPath(outline, paint(Paint.Style.FILL, path.fillAlpha * brush.alpha()))
        }
        path.stroke?.takeIf { path.strokeLineWidth > 0f }?.let { brush ->
            canvas.drawPath(
                outline,
                paint(Paint.Style.STROKE, path.strokeAlpha * brush.alpha()).apply {
                    strokeWidth = path.strokeLineWidth
                    strokeMiter = path.strokeLineMiter
                    strokeCap = when (path.strokeLineCap) {
                        StrokeCap.Round -> Paint.Cap.ROUND
                        StrokeCap.Square -> Paint.Cap.SQUARE
                        else -> Paint.Cap.BUTT
                    }
                    strokeJoin = when (path.strokeLineJoin) {
                        StrokeJoin.Round -> Paint.Join.ROUND
                        StrokeJoin.Bevel -> Paint.Join.BEVEL
                        else -> Paint.Join.MITER
                    }
                },
            )
        }
    }

    private fun paint(style: Paint.Style, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = style
        color = android.graphics.Color.WHITE
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
    }

    private fun androidx.compose.ui.graphics.Brush.alpha(): Float = (this as? SolidColor)?.value?.alpha ?: 1f

    private fun List<PathNode>.toAndroidPath(fillType: PathFillType): Path =
        PathParser().addPathNodes(this).toPath().asAndroidPath().apply {
            this.fillType = if (fillType == PathFillType.EvenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        }
}
