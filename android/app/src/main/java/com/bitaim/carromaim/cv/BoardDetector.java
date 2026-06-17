package com.bitaim.carromaim.cv;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

/**
 * BoardDetector v10 — Low-End Optimised Build
 *
 * Low-end optimizations added:
 *  – PROC_W is chosen at runtime: 240 px on devices with < 1.5 GB RAM,
 *    360 px otherwise. Smaller proc resolution cuts memory ~2.25× and
 *    speeds up the scan ~55%.
 *  – SCAN_STEP raised from 3 → 4 on low-end, reducing pixel visits ~44%.
 *  – EMA_A raised slightly on low-end so fewer frames needed to track.
 *
 * Detection pipeline (unchanged):
 *  1. detectByOrangeDensity()   — primary
 *  2. detectByPocketQuadrants() — secondary
 *  3. detectByWoodSurface()     — tertiary
 *  4. smartFallback()           — guaranteed
 */
public class BoardDetector {

    private static final String TAG = "BoardDetector";

    private static final float POCKET_INSET   = 0.060f;
    private static final float STRIKER_Y_FRAC = 0.800f;
    private static final float COIN_R_MIN     = 0.018f;
    private static final float COIN_R_MAX     = 0.035f;

    private static final int PX_BOARD_BORDER = 10;
    private static final int PX_BLUE         = 11;

    private int  procW;
    private float emaA;
    private int  scanStep;

    private float lastValidMinR = 0f;
    private float lastValidMaxR = 0f;

    private TFLiteDetector aiDetector;

    private RectF smoothedBoard = null;
    private int[] pixelBuf      = null;

    public BoardDetector() { this(null); }

    public BoardDetector(Context ctx) {
        boolean lowEnd = isLowEndDevice(ctx);
        procW    = lowEnd ? 240 : 360;
        emaA     = lowEnd ? 0.14f : 0.10f;
        scanStep = lowEnd ? 4 : 3;
        Log.i(TAG, "BoardDetector init: procW=" + procW + " lowEnd=" + lowEnd);
    }

    public void setAiDetector(TFLiteDetector detector) {
        this.aiDetector = detector;
    }

    public synchronized void setScreenSize(Context ctx) {
        boolean lowEnd = isLowEndDevice(ctx);
        procW    = lowEnd ? 240 : 360;
        emaA     = lowEnd ? 0.14f : 0.10f;
        scanStep = lowEnd ? 4 : 3;
        Log.i(TAG, "BoardDetector init: procW=" + procW + " lowEnd=" + lowEnd);
    }

    private static boolean isLowEndDevice(Context ctx) {
        if (ctx == null) return false;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return false;
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return mi.totalMem < 1536L * 1024 * 1024;
        } catch (Throwable t) { return false; }
    }

    public void setMinRadiusFrac(float v) {}
    public void setMaxRadiusFrac(float v) {}
    public void setParam2(double v)       {}

    public synchronized GameState detect(Bitmap src) {
        if (src == null) return null;
        try { return run(src); }
        catch (Throwable t) {
            Log.e(TAG, "detect error: " + t.getMessage());
            return fallbackState(src.getWidth(), src.getHeight());
        }
    }

    private GameState run(Bitmap src) {
        int srcW = src.getWidth(), srcH = src.getHeight();
        if (srcW == 0 || srcH == 0) return null;

        float scale = Math.min(1f, (float) procW / srcW);
        int   pW    = Math.round(srcW * scale);
        int   pH    = Math.round(srcH * scale);

        Bitmap bmp = (scale < 0.99f)
                ? Bitmap.createScaledBitmap(src, pW, pH, false) : src;

        int total = pW * pH;
        if (pixelBuf == null || pixelBuf.length < total) pixelBuf = new int[total];
        bmp.getPixels(pixelBuf, 0, pW, 0, 0, pW, pH);
        if (bmp != src) bmp.recycle();

        RectF rawBoard = detectByOrangeDensity(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = detectByPocketQuadrants(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = detectByWoodSurface(pixelBuf, pW, pH);
        if (rawBoard == null) rawBoard = smartFallback(pW, pH);

        smoothedBoard = smoothRect(smoothedBoard, rawBoard);
        RectF pb = smoothedBoard;
        float inv = 1f / scale;
        RectF srcBoard = scaleRect(pb, inv);

        // ---------------------------------------------------------
        // AI DETECTION (Option 3 - Machine Learning)
        // ---------------------------------------------------------
        if (aiDetector != null && aiDetector.isReady()) {
            List<Coin> aiCoins = aiDetector.detectObjects(src, srcBoard);
            if (!aiCoins.isEmpty()) {
                GameState s = new GameState();
                s.board = srcBoard;
                for (Coin c : aiCoins) {
                    if (c.color == Coin.COLOR_STRIKER || c.isStriker) {
                        s.striker = c;
                    } else if (c.color != PX_BLUE) {
                        s.coins.add(c);
                    }
                }
                addPockets(s);
                return s;
            }
        }

        // ---------------------------------------------------------
        // CV DETECTION FALLBACK (If AI fails or model is missing)
        // ---------------------------------------------------------
        float minR = pb.width() * COIN_R_MIN;
        float maxR = pb.width() * COIN_R_MAX;
        List<Coin> coins = detectCoins(pixelBuf, pW, pH, pb, minR, maxR);

        List<Coin> scaled = new ArrayList<>(coins.size());
        for (Coin c : coins)
            scaled.add(new Coin(c.pos.x * inv, c.pos.y * inv,
                    c.radius * inv, c.color, false));

        float strikerThreshY = (pb.top + pb.height() * 0.68f) * inv;
        float boardCX        = srcBoard.centerX();
        float strikerZoneW   = srcBoard.width() * 0.70f;

        Coin striker = null;
        for (Coin c : scaled) {
            if (c.color != PX_BLUE) continue;
            if (c.pos.y < strikerThreshY) continue;
            if (striker == null || c.radius > striker.radius) striker = c;
        }
        if (striker == null) {
            for (Coin c : scaled) {
                if (c.color != Coin.COLOR_WHITE) continue;
                if (c.pos.y < strikerThreshY) continue;
                if (Math.abs(c.pos.x - boardCX) > strikerZoneW) continue;
                if (striker == null || c.radius > striker.radius) striker = c;
            }
        }
        if (striker != null) { striker.isStriker = true; striker.color = Coin.COLOR_STRIKER; }

        GameState s = new GameState();
        s.board = srcBoard;

        if (striker != null) {
            s.striker = striker;
        } else {
            float defX = srcBoard.centerX();
            float defY = srcBoard.top + srcBoard.height() * STRIKER_Y_FRAC;
            float defR = srcBoard.width() * 0.028f;
            s.striker  = new Coin(defX, defY, defR, Coin.COLOR_STRIKER, true);
        }

        for (Coin c : scaled) {
            if (c == striker) continue;
            if (c.color == PX_BLUE) continue;
            s.coins.add(c);
        }

        addPockets(s);
        return s;
    }

    private RectF detectByOrangeDensity(int[] px, int w, int h) {
        int densityThresh = Math.max(3, w / 30);
        int topRow = -1, bottomRow = -1;
        for (int y = 0; y < h; y += scanStep) {
            int cnt = 0;
            for (int x = 0; x < w; x += scanStep)
                if (isOrangeBorder(px[y * w + x])) cnt++;
            if (cnt >= densityThresh) {
                if (topRow < 0) topRow = y;
                bottomRow = y;
            }
        }
        if (topRow < 0 || (bottomRow - topRow) < h * 0.10f) return null;
        int leftCol = -1, rightCol = -1;
        for (int x = 0; x < w; x += scanStep) {
            int cnt = 0;
            for (int y = topRow; y <= bottomRow; y += scanStep)
                if (isOrangeBorder(px[y * w + x])) cnt++;
            if (cnt >= densityThresh) {
                if (leftCol < 0) leftCol = x;
                rightCol = x;
            }
        }
        if (leftCol < 0 || (rightCol - leftCol) < w * 0.15f) return null;
        float boardW = rightCol - leftCol, boardH = bottomRow - topRow;
        float aspect = Math.max(boardW, boardH) / Math.max(1, Math.min(boardW, boardH));
        if (aspect > 1.60f) return null;
        float side = Math.min(boardW, boardH);
        float cx = (leftCol + rightCol) / 2f, cy = (topRow + bottomRow) / 2f;
        return new RectF(Math.max(0, cx-side/2f), Math.max(0, cy-side/2f),
                Math.min(w, cx+side/2f), Math.min(h, cy+side/2f));
    }

    private boolean isOrangeBorder(int p) {
        int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
        if (r > 150 && g > 60 && g < 170 && b < 75 && r > g * 1.3f && g > b + 20 && (r-b) > 100) return true;
        if (r > 110 && r < 200 && g > 55 && g < 130 && b < 70 && r > g * 1.2f && (r-b) > 65) return true;
        return false;
    }

    private RectF detectByPocketQuadrants(int[] px, int w, int h) {
        int mX = w/2, mY = h/2;
        PointF tl = darkBlobInRegion(px, w, 0, 0, mX, mY);
        PointF tr = darkBlobInRegion(px, w, mX, 0, w, mY);
        PointF bl = darkBlobInRegion(px, w, 0, mY, mX, h);
        PointF br = darkBlobInRegion(px, w, mX, mY, w, h);
        if (tl == null || tr == null || bl == null || br == null) return null;
        float spanW = ((tr.x-tl.x)+(br.x-bl.x))*0.5f;
        float spanH = ((bl.y-tl.y)+(br.y-tr.y))*0.5f;
        float aspect = Math.max(spanW, spanH) / Math.max(1, Math.min(spanW, spanH));
        if (aspect > 1.40f) return null;
        float side = Math.min(spanW, spanH);
        float cx = (tl.x+tr.x+bl.x+br.x)/4f, cy = (tl.y+tr.y+bl.y+br.y)/4f;
        return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
    }

    private PointF darkBlobInRegion(int[] px, int w, int x0, int y0, int x1, int y1) {
        long sumX=0, sumY=0, cnt=0;
        int mx=(x0+x1)/2, my=(y0+y1)/2;
        long[] qSumX=new long[4], qSumY=new long[4], qCnt=new long[4];
        for (int y=y0; y<y1; y+=scanStep) {
            for (int x=x0; x<x1; x+=scanStep) {
                if (x<0||x>=w||y<0) continue;
                int c = px[y*w+x];
                int lum = ((c>>16)&0xFF)+((c>>8)&0xFF)+(c&0xFF);
                if (lum < 100) {
                    sumX+=x; sumY+=y; cnt++;
                    int qi=(x<mx?0:1)+(y<my?0:2);
                    qSumX[qi]+=x; qSumY[qi]+=y; qCnt[qi]++;
                }
            }
        }
        if (cnt < 5) return null;
        int best=0;
        for (int q=1; q<4; q++) if (qCnt[q]>qCnt[best]) best=q;
        if (qCnt[best] >= cnt/2)
            return new PointF((float)qSumX[best]/qCnt[best], (float)qSumY[best]/qCnt[best]);
        return new PointF((float)sumX/cnt, (float)sumY/cnt);
    }

    private RectF detectByWoodSurface(int[] px, int w, int h) {
        int margin = (int)(w * 0.05f);
        int minX=w, maxX=0, minY=h, maxY=0, cnt=0;
        for (int y=margin; y<h-margin; y+=scanStep) {
            for (int x=margin; x<w-margin; x+=scanStep) {
                if (isWoodSurface(px[y*w+x])) {
                    if (x<minX) minX=x; if (x>maxX) maxX=x;
                    if (y<minY) minY=y; if (y>maxY) maxY=y;
                    cnt++;
                }
            }
        }
        float minSpan = w * 0.25f;
        if (cnt < 20 || (maxX-minX) < minSpan || (maxY-minY) < minSpan) return null;
        float side = Math.min(maxX-minX, maxY-minY) * (1f + 2*0.08f);
        float cx = (minX+maxX)/2f, cy = (minY+maxY)/2f;
        return new RectF(Math.max(0,cx-side/2f), Math.max(0,cy-side/2f),
                Math.min(w,cx+side/2f), Math.min(h,cy+side/2f));
    }

    private boolean isWoodSurface(int p) {
        int r=(p>>16)&0xFF, g=(p>>8)&0xFF, b=p&0xFF;
        int lum=(r+g+b)/3;
        if (lum<110||lum>230) return false;
        if (r<=g||g<=b) return false;
        if ((r-b)>130) return false;
        if ((r-b)<30) return false;
        if (r<150||r>235||g<110||g>195||b<60||b>155) return false;
        return true;
    }

    private RectF smartFallback(int w, int h) {
        float side = w * 0.94f;
        float cx = w/2f;
        float uiTop = h*0.14f, uiBot = h*0.10f;
        float cy = uiTop + (h-uiTop-uiBot)*0.50f;
        side = Math.min(side, (h-uiTop-uiBot)*0.96f);
        return new RectF(cx-side/2f, cy-side/2f, cx+side/2f, cy+side/2f);
    }

    /**
     * Coin detection via density-map + local-maxima peak search.
     *
     * Algorithm:
     *  1. Classify each pixel by color (white/black/red/blue).
     *  2. Each matching pixel "votes" by incrementing all cells within a
     *     circular kernel of radius kernR in a per-color density grid.
     *  3. Find local maxima in each grid — each peak = one coin centre.
     *  4. Hard-cap per color (9 white, 9 black, 1 red, 1 striker).
     *  5. Final NMS removes any remaining cross-color overlaps.
     */
    private List<Coin> detectCoins(int[] px, int w, int h, RectF board, float minR, float maxR) {
        // 9% inset on each side to skip orange border + inner decorative frame
        int bL = Math.max(0, (int)(board.left   + board.width()  * 0.09f));
        int bR = Math.min(w, (int)(board.right  - board.width()  * 0.09f));
        int bT = Math.max(0, (int)(board.top    + board.height() * 0.09f));
        int bB = Math.min(h, (int)(board.bottom - board.height() * 0.09f));
        int gw = bR - bL, gh = bB - bT;
        if (gw <= 0 || gh <= 0) return new ArrayList<>();

        // Per-color vote maps (density grids)
        int cells = gw * gh;
        float[] wMap = new float[cells]; // white coins
        float[] bMap = new float[cells]; // black coins
        float[] rMap = new float[cells]; // red queen
        float[] sMap = new float[cells]; // striker (blue-grey)

        // Spread radius — slightly smaller than coin radius so peaks stay sharp
        int kernR = Math.max(2, (int)(maxR * 0.55f));
        int kernR2 = kernR * kernR;

        for (int y = bT; y < bB; y += scanStep) {
            for (int x = bL; x < bR; x += scanStep) {
                int color = classifyPx(px[y * w + x]);
                if (color < 0) continue;
                float[] map;
                switch (color) {
                    case Coin.COLOR_WHITE: map = wMap; break;
                    case Coin.COLOR_BLACK: map = bMap; break;
                    case Coin.COLOR_RED:   map = rMap; break;
                    case PX_BLUE:          map = sMap; break;
                    default: continue;
                }
                int gx = x - bL, gy = y - bT;
                // Accumulate circular vote kernel
                for (int dy2 = -kernR; dy2 <= kernR; dy2++) {
                    int gy2 = gy + dy2;
                    if (gy2 < 0 || gy2 >= gh) continue;
                    for (int dx2 = -kernR; dx2 <= kernR; dx2++) {
                        if (dx2 * dx2 + dy2 * dy2 > kernR2) continue;
                        int gx2 = gx + dx2;
                        if (gx2 < 0 || gx2 >= gw) continue;
                        map[gy2 * gw + gx2] += 1f;
                    }
                }
            }
        }

        float coinR   = (minR + maxR) * 0.5f;
        float queenR  = coinR * 0.72f;
        float strikerR= coinR * 1.15f;
        int   suppR   = (int)(coinR * 1.9f);          // suppression radius between coins
        // Minimum peak value: expect at least ~25% of kernel area to be filled
        float minPeak = (float)(Math.PI * kernR2 * 0.25f / (scanStep * scanStep));
        float minPeakSmall = minPeak * 0.3f;          // relaxed for red queen / striker

        List<Coin> out = new ArrayList<>();
        peakDetect(wMap, gw, gh, Coin.COLOR_WHITE, bL, bT, coinR,    suppR,     minPeak,      9, out);
        peakDetect(bMap, gw, gh, Coin.COLOR_BLACK, bL, bT, coinR,    suppR,     minPeak,      9, out);
        peakDetect(rMap, gw, gh, Coin.COLOR_RED,   bL, bT, queenR,   suppR,     minPeakSmall, 1, out);
        peakDetect(sMap, gw, gh, PX_BLUE,          bL, bT, strikerR, suppR * 2, minPeakSmall, 2, out);
        nms(out);
        return out;
    }

    /**
     * Finds up to maxCoins local maxima in `map` above `threshold`,
     * using greedy suppression within suppR pixels of each accepted peak.
     */
    private void peakDetect(float[] map, int w, int h, int color,
                             int offX, int offY, float coinR,
                             int suppR, float threshold, int maxCoins,
                             List<Coin> out) {
        // Collect candidate local maxima
        List<int[]> peaks = new ArrayList<>();
        for (int y = 1; y < h - 1; y++) {
            for (int x = 1; x < w - 1; x++) {
                float v = map[y * w + x];
                if (v < threshold) continue;
                // 3×3 strict local maximum
                boolean isMax = true;
                outer:
                for (int dy = -1; dy <= 1; dy++)
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        if (map[(y + dy) * w + (x + dx)] >= v) { isMax = false; break outer; }
                    }
                if (isMax) peaks.add(new int[]{x, y, (int)(v * 100)});
            }
        }

        // Sort highest confidence first
        peaks.sort((a, b) -> b[2] - a[2]);

        // Greedy NMS — keep up to maxCoins non-overlapping peaks
        boolean[] used = new boolean[peaks.size()];
        int suppR2 = suppR * suppR;
        int added  = 0;
        for (int i = 0; i < peaks.size() && added < maxCoins; i++) {
            if (used[i]) continue;
            int[] p = peaks.get(i);
            out.add(new Coin(p[0] + offX, p[1] + offY, coinR, color, false));
            added++;
            for (int j = i + 1; j < peaks.size(); j++) {
                if (used[j]) continue;
                int dx = peaks.get(j)[0] - p[0], dy = peaks.get(j)[1] - p[1];
                if (dx * dx + dy * dy < suppR2) used[j] = true;
            }
        }
    }

    private int classifyPx(int p) {
        int r=(p>>16)&0xFF, g=(p>>8)&0xFF, b=p&0xFF;
        int lum=(r+g+b)/3;
        int maxC=Math.max(r,Math.max(g,b)), minC=Math.min(r,Math.min(g,b));
        int sat=maxC-minC;

        // White/cream coins:
        // Must be MUCH brighter than wood surface (wood lum ~110-145)
        // Must be low saturation (neutral), and blue channel must be high (>130)
        // This excludes warm wood tones which have low blue channel
        if (lum > 170 && sat < 55 && b > 140 && r > 150 && g > 145) return Coin.COLOR_WHITE;

        // Black coins — very dark, near-zero luminosity
        if (lum < 52 && maxC < 75) return Coin.COLOR_BLACK;

        // Red queen — bright red, low green and blue
        if (r > 145 && g < 85 && b < 85 && r > g * 1.9f && r > b * 1.8f) return Coin.COLOR_RED;

        // Striker — blue-grey disc (noticeably more blue than red)
        if (b > r + 22 && b > g + 18 && b > 105 && lum > 85 && lum < 195) return PX_BLUE;

        return -1;
    }

    private void nms(List<Coin> coins) {
        boolean[] keep=new boolean[coins.size()];
        Arrays.fill(keep,true);
        for (int i=0; i<coins.size(); i++) {
            if (!keep[i]) continue;
            Coin a=coins.get(i);
            for (int j=i+1; j<coins.size(); j++) {
                if (!keep[j]) continue;
                Coin b=coins.get(j);
                float dx=a.pos.x-b.pos.x, dy=a.pos.y-b.pos.y;
                float d=(float)Math.sqrt(dx*dx+dy*dy);
                // Stricter overlap threshold (0.5 vs 0.65) to suppress more false duplicates
                if (d<(a.radius+b.radius)*0.50f) {
                    if (a.radius>=b.radius) keep[j]=false;
                    else { keep[i]=false; break; }
                }
            }
        }
        Iterator<Coin> it=coins.iterator(); int idx=0;
        while (it.hasNext()) { it.next(); if (!keep[idx++]) it.remove(); }
    }

    private GameState fallbackState(int w, int h) {
        GameState s=new GameState();
        float side=w*0.94f;
        float uiTop=h*0.14f, uiBot=h*0.10f;
        float cy=uiTop+(h-uiTop-uiBot)*0.50f;
        side=Math.min(side,(h-uiTop-uiBot)*0.96f);
        float cx=w/2f;
        s.board=new RectF(cx-side/2f,cy-side/2f,cx+side/2f,cy+side/2f);
        float defY=s.board.top+s.board.height()*STRIKER_Y_FRAC;
        s.striker=new Coin(cx,defY,side*0.028f,Coin.COLOR_STRIKER,true);
        float r=side*0.022f;
        s.coins.add(new Coin(cx,cy-side*0.10f,r,Coin.COLOR_WHITE,false));
        s.coins.add(new Coin(cx-side*0.12f,cy,r,Coin.COLOR_BLACK,false));
        s.coins.add(new Coin(cx+side*0.12f,cy,r,Coin.COLOR_BLACK,false));
        s.coins.add(new Coin(cx,cy,r,Coin.COLOR_RED,false));
        addPockets(s);
        return s;
    }

    private void addPockets(GameState s) {
        if (s.board==null) return;
        float i=s.board.width()*POCKET_INSET;
        s.pockets.add(new PointF(s.board.left+i,  s.board.top+i));
        s.pockets.add(new PointF(s.board.right-i, s.board.top+i));
        s.pockets.add(new PointF(s.board.left+i,  s.board.bottom-i));
        s.pockets.add(new PointF(s.board.right-i, s.board.bottom-i));
    }

    private RectF scaleRect(RectF r, float s) {
        return new RectF(r.left*s,r.top*s,r.right*s,r.bottom*s);
    }

    private RectF smoothRect(RectF p, RectF n) {
        if (p==null) return n; if (n==null) return p;
        return new RectF(
                p.left  +emaA*(n.left  -p.left),
                p.top   +emaA*(n.top   -p.top),
                p.right +emaA*(n.right -p.right),
                p.bottom+emaA*(n.bottom-p.bottom));
    }
}
