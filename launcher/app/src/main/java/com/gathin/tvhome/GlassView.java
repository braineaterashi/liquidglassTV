package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a faint outer glow.
 * One instance floats over the grid and is moved with spring animations between tiles.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private float radius;
    /** Glow colour as 0xRRGGBB: white normally, a festival's accent (diya gold) while a festive skin is on. */
    private int glowRgb = 0xFFFFFF;
    /** Glow strength: vibrant outer neon glow. */
    private int glowAlpha = 0xD0;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 18f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(2.0f * density);
        rim.setColor(0xCCFFFFFF);
        glow.setStyle(Paint.Style.STROKE);
        // Cache the slab as a texture. The spring only changes translation, so the GPU can
        // move it without replaying clipPath and the glow on every frame.
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    /** Tints the rim and the outer glow; 0 restores the plain white glass. */
    void setAccent(int argb) {
        if (argb == 0) {
            glowRgb = 0xFFFFFF;
            glowAlpha = 0x50;
            rim.setColor(0xCCFFFFFF);
        } else {
            glowRgb = argb & 0xFFFFFF;
            glowAlpha = 0xE0;
            // Vivid neon rim lifted slightly with specular white for optical glass sheen
            int r = Math.min(255, (((glowRgb >> 16) & 0xFF) * 3 + 255) / 4);
            int g = Math.min(255, (((glowRgb >> 8) & 0xFF) * 3 + 255) / 4);
            int b = Math.min(255, (((glowRgb & 0xFF) * 3 + 255) / 4));
            rim.setColor(0xFF000000 | (r << 16) | (g << 8) | b);
        }
        invalidate();
    }

    void setRadius(float px) {
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fill.setShader(new LinearGradient(0, 0, 0, h, 0x12FFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP));
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.35f, 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 10f * density;   // room for the vibrant glow halo
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        // Radiant neon glow: 20 smooth concentric rings radiating seamlessly from the icon boundary
        int rings = 20;
        float step = inset / rings;
        glow.setStrokeWidth(step + 0.6f);
        for (int i = 0; i < rings; i++) {
            float o = step * (i + 0.5f);
            float t = 1f - (float) i / rings;
            float falloff = (float) Math.pow(t, 1.5);
            glow.setColor(Math.round(glowAlpha * falloff) << 24 | glowRgb);
            float rOut = radius + o * 0.7f;
            canvas.drawRoundRect(rect.left - o, rect.top - o, rect.right + o, rect.bottom + o, rOut, rOut, glow);
        }

        // Delicate top specular reflection (no hard boundary lines or inner strokes)
        canvas.save();
        canvas.clipRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.30f);
        canvas.drawRoundRect(rect, radius, radius, specular);
        canvas.restore();
    }
}
