package io.github.privatednsfallback;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.view.View;

/** A compact schematic of the configured route. No packet or traffic counters are implied. */
final class RouteDiagram extends View {
  private final Paint pen = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final int accent = 0xffa7efd7, muted = 0xffa6beb8;
  private boolean vpn, resolver;

  RouteDiagram(Context c) {
    super(c);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void route(boolean connected, boolean healthy) {
    if (vpn != connected || resolver != healthy) {
      vpn = connected;
      resolver = healthy;
      invalidate();
    }
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float d = getResources().getDisplayMetrics().density;
    float s = getResources().getDisplayMetrics().scaledDensity;
    float l = 20 * d, r = getWidth() - 25 * d, m = (l + r) / 2, y = 14 * d;
    pen.setStrokeWidth(1.5f * d);
    pen.setStrokeCap(Paint.Cap.ROUND);
    pen.setColor(vpn ? accent : muted);
    c.drawLine(l + 12 * d, y, m - 13 * d, y, pen);
    pen.setColor(resolver ? accent : muted);
    c.drawLine(m + 13 * d, y, r - 13 * d, y, pen);
    pen.setStyle(Paint.Style.STROKE);
    pen.setColor(accent);
    c.drawRoundRect(l - 5 * d, y - 8 * d, l + 5 * d, y + 8 * d, 2 * d, 2 * d, pen);
    c.drawPoint(l, y + 5 * d, pen);
    pen.setColor(vpn ? accent : muted);
    Path shield = new Path();
    shield.moveTo(m, y - 8 * d);
    shield.lineTo(m + 7 * d, y - 5 * d);
    shield.lineTo(m + 6 * d, y + 2 * d);
    shield.quadTo(m + 6 * d, y + 5 * d, m, y + 9 * d);
    shield.quadTo(m - 7 * d, y + 5 * d, m - 7 * d, y + 1 * d);
    shield.lineTo(m - 7 * d, y - 5 * d);
    shield.close();
    c.drawPath(shield, pen);
    pen.setColor(resolver ? accent : muted);
    c.drawRoundRect(r - 7 * d, y - 7 * d, r + 7 * d, y - 1 * d, 1.5f * d, 1.5f * d, pen);
    c.drawRoundRect(r - 7 * d, y + 2 * d, r + 7 * d, y + 8 * d, 1.5f * d, 1.5f * d, pen);
    pen.setStyle(Paint.Style.FILL);
    pen.setTextAlign(Paint.Align.CENTER);
    pen.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    pen.setTextSize(11 * s);
    pen.setColor(0xffd5e9e3);
    float base = y + 10 * d + 14 * s;
    c.drawText("Phone", l, base, pen);
    c.drawText("VPN", m, base, pen);
    c.drawText("Resolver", r, base, pen);
  }
}
