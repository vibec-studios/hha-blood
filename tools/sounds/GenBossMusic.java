import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

/**
 * Boss soundtrack for "Die Schlachtgrube" (Blood Cerberus -> Crimson Minotaur "Der Schlaechter").
 * Pure Java, JDK only, deterministic: every instrument is synthesised (subtractive strings/brass with
 * PolyBLEP oscillators, formant choir, pitched-noise war drums, inharmonic anvil/metal/bell, sub bass),
 * mixed per track with an FDN stereo reverb + early reflections, EQ, compressor, look-ahead limiter and
 * loudness normalisation (BS.1770 K-weighted). Seamless loops are rendered into a circular buffer of exactly
 * the loop length (all tails wrap around), bus processors run twice over the loop so their state is periodic.
 *
 * One leitmotif (D Phrygian / harmonic minor, 4+4 bars: THEME_A open on the dominant, THEME_B closed on D):
 *   cerberus            hunt version over a galloping string ostinato, "three heads" call/response of the head motif
 *   cerberus_unleashed  diminution (twice as fast, an octave higher), 3+3+2 16th ostinato, choir
 *   minotaur            augmentation in low brass, circling 7-note "labyrinth" ostinato, anvils, accelerating drums
 *   minotaur_final      both ostinati at once, augmented theme in the bass against the head motif sequence and the
 *                       full theme in choir + brass
 *   victory             the motif turns to D major (Picardy third)
 *
 * Usage: java tools/sounds/GenBossMusic.java [outDir] [workDir] [ffmpeg.exe] [onlyTrack,onlyTrack...]
 *   outDir  default blood/src/main/resources/assets/hha_blood/sounds/music (receives the .ogg files)
 *   workDir default build/music_work (receives the .wav files)
 *   ffmpeg  optional; when given, every WAV is converted with libvorbis -q:a 4 (tension: 6)
 */
public class GenBossMusic {
    static final int SR = 44100;
    static final double TAU = 2 * Math.PI;

    // =====================================================================================  harmony
    static final class Ch {
        final String name; final int root; final int[] iv;
        Ch(String name, int root, int... iv) { this.name = name; this.root = root; this.iv = iv; }
        boolean has(int p) { int pc = Math.floorMod(p, 12); for (int i : iv) if ((root + i) % 12 == pc) return true; return false; }
    }
    static final Map<String, Ch> CH = new HashMap<>();
    static void def(String n, int root, int... iv) { CH.put(n, new Ch(n, root, iv)); }
    static {
        def("Dm", 2, 0, 3, 7); def("D", 2, 0, 4, 7); def("D5", 2, 0, 7); def("Eb", 3, 0, 4, 7); def("Bb", 10, 0, 4, 7);
        def("Gm", 7, 0, 3, 7); def("A", 9, 0, 4, 7); def("A5", 9, 0, 7); def("Asus", 9, 0, 5, 7); def("Cm", 0, 0, 3, 7);
        def("C", 0, 0, 4, 7); def("F", 5, 0, 4, 7);
    }
    static Ch ch(String s) { Ch c = CH.get(s); if (c == null) throw new IllegalArgumentException("chord " + s); return c; }

    static double mtof(double m) { return 440.0 * Math.pow(2, (m - 69) / 12.0); }
    static int rootIn(Ch c, int lo) { int p = lo; while (Math.floorMod(p, 12) != c.root) p++; return p; }

    /** D Phrygian scale degrees relative to D (D Eb F G A Bb C). */
    static final int[] REL = {0, 1, 3, 5, 7, 8, 10};
    /** move m by scale steps; lead = raise C to C# (leading tone) */
    static int step(int m, int steps, boolean lead) {
        if (steps == 0) return m;
        int rel = Math.floorMod(m - 2, 12), o = m - rel, idx = 0;
        for (int k = 0; k < 7; k++) if (REL[k] <= rel) idx = k;
        int k = idx + steps;
        int p = o + REL[Math.floorMod(k, 7)] + 12 * Math.floorDiv(k, 7);
        if (lead && Math.floorMod(p - 2, 12) == 10) p += 1;
        return p;
    }

    static int nearest(List<Integer> c, double t) { int b = c.get(0); for (int x : c) if (Math.abs(x - t) < Math.abs(b - t)) b = x; return b; }
    static int nearestPc(List<Integer> c, int from, int pc) { int b = -1; for (int x : c) if (Math.floorMod(x, 12) == pc && (b < 0 || Math.abs(x - from) < Math.abs(b - from))) b = x; return b; }
    static int nextAbove(List<Integer> c, int x) { for (int y : c) if (y > x) return y; return x; }

    /** voice-leading: keep each voice as close as possible to the previous chord, cover root/third/fifth */
    static int[] voice(Ch c, int[] prev, int lo, int hi, int nv) {
        List<Integer> cand = new ArrayList<>();
        for (int p = lo; p <= hi; p++) if (c.has(p)) cand.add(p);
        int[] v = new int[nv];
        for (int k = 0; k < nv; k++) {
            double tgt = prev == null ? lo + (hi - lo) * (k + 0.5) / nv : prev[Math.min(k, prev.length - 1)];
            v[k] = nearest(cand, tgt);
        }
        Arrays.sort(v);
        for (int k = 1; k < nv; k++) if (v[k] <= v[k - 1]) v[k] = nextAbove(cand, v[k - 1]);
        for (int t = 0; t < Math.min(3, c.iv.length); t++) {
            int pc = (c.root + c.iv[t]) % 12;
            boolean present = false;
            for (int x : v) if (Math.floorMod(x, 12) == pc) present = true;
            if (present) continue;
            int best = -1; double bestCost = 1e9;
            for (int k = 0; k < nv; k++) {
                int pck = Math.floorMod(v[k], 12), count = 0;
                for (int x : v) if (Math.floorMod(x, 12) == pck) count++;
                if (count < 2) continue;
                int c2 = nearestPc(cand, v[k], pc);
                if (c2 >= 0 && Math.abs(c2 - v[k]) < bestCost) { bestCost = Math.abs(c2 - v[k]); best = k; }
            }
            if (best >= 0) v[best] = nearestPc(cand, v[best], pc);
        }
        Arrays.sort(v);
        for (int k = 1; k < nv; k++) if (v[k] <= v[k - 1]) v[k] = nextAbove(cand, v[k - 1]);
        return v;
    }

    // ------------------------------------------------------------------ the leitmotif {semitones from D, beats}
    static final double[][] THEME_A = {
        {0, 1}, {1, .5}, {0, .5}, {-5, 2},
        {-4, 1}, {-2, .5}, {-4, .5}, {-5, 1}, {-7, 1},
        {0, 1}, {1, .5}, {0, .5}, {3, 1}, {1, 1},
        {0, 1}, {-1, 1}, {-5, 2}};
    static final double[][] THEME_B = {
        {0, 1}, {1, .5}, {0, .5}, {-5, 2},
        {-4, 1}, {-2, .5}, {0, .5}, {1, 1}, {3, 1},
        {5, 1.5}, {3, .5}, {1, 1}, {0, 1},
        {0, 1.5}, {-1, .5}, {0, 2}};
    static final String PA = "Dm Gm Bb A5|A|A|A", PB = "Dm Bb Cm A5|A5|Dm|Dm";
    static final String PAD = "Dm|Gm Bb|A", PBD = "Dm|Bb Cm|Cm|A|Dm";          // diminution, 2 bars each
    static final String PAA = "Dm Dm Gm Gm Bb Bb A5|A A";                        // augmentation, 8 bars

    // =====================================================================================  DSP basics
    static double clamp(double x, double a, double b) { return x < a ? a : (x > b ? b : x); }
    static double smooth(double x) { if (x <= 0) return 0; if (x >= 1) return 1; return x * x * (3 - 2 * x); }
    static double relShape(double x) { if (x <= 0) return 1; if (x >= 1) return 0; return 0.5 * (1 + Math.cos(Math.PI * x)); }
    static double blep(double t, double dt) {
        if (t < dt) { t /= dt; return t + t - t * t - 1.0; }
        if (t > 1.0 - dt) { t = (t - 1.0) / dt; return t * t + t + t + 1.0; }
        return 0.0;
    }
    static void fadeTail(float[] o, double sec) {
        int m = Math.min(o.length, (int) (sec * SR));
        for (int i = 0; i < m; i++) o[o.length - 1 - i] *= (float) ((double) i / m);
    }

    static final class Noise {
        long s;
        Noise(long seed) { s = seed ^ 0x9E3779B97F4A7C15L; if (s == 0) s = 1; }
        double next() { s ^= s << 13; s ^= s >>> 7; s ^= s << 17; return ((s >>> 11) * 0x1.0p-53) * 2 - 1; }
    }

    /** zero-delay-feedback state variable filter (Simper) */
    static final class SVF {
        double ic1, ic2, a1, a2, a3, k = 1.4, v1, v2;
        SVF set(double fc, double q) {
            fc = clamp(fc, 5, SR * 0.45);
            double g = Math.tan(Math.PI * fc / SR);
            k = 1.0 / q; a1 = 1.0 / (1.0 + g * (g + k)); a2 = g * a1; a3 = g * a2;
            return this;
        }
        void tick(double v0) {
            double v3 = v0 - ic2;
            v1 = a1 * ic1 + a2 * v3; v2 = ic2 + a2 * ic1 + a3 * v3;
            ic1 = 2 * v1 - ic1; ic2 = 2 * v2 - ic2;
        }
        double lp(double x) { tick(x); return v2; }
        double bp(double x) { tick(x); return v1 * k; }
        double hp(double x) { tick(x); return x - k * v1 - v2; }
    }

    /** RBJ biquad, transposed direct form II */
    static final class Bq {
        double b0, b1, b2, a1, a2, z1, z2;
        Bq(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = b0 / a0; this.b1 = b1 / a0; this.b2 = b2 / a0; this.a1 = a1 / a0; this.a2 = a2 / a0;
        }
        double p(double x) { double y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
        static Bq hp(double f, double q) {
            double w = TAU * f / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Bq((1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al);
        }
        static Bq peak(double f, double q, double db) {
            double A = Math.pow(10, db / 40), w = TAU * f / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * q);
            return new Bq(1 + al * A, -2 * cs, 1 - al * A, 1 + al / A, -2 * cs, 1 - al / A);
        }
        static Bq shelf(double f, double db, double q, boolean high) {
            double A = Math.pow(10, db / 40), w = TAU * f / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * q), sa = 2 * Math.sqrt(A) * al;
            if (high) return new Bq(A * ((A + 1) + (A - 1) * cs + sa), -2 * A * ((A - 1) + (A + 1) * cs), A * ((A + 1) + (A - 1) * cs - sa),
                    (A + 1) - (A - 1) * cs + sa, 2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - sa);
            return new Bq(A * ((A + 1) - (A - 1) * cs + sa), 2 * A * ((A - 1) - (A + 1) * cs), A * ((A + 1) - (A - 1) * cs - sa),
                    (A + 1) + (A - 1) * cs + sa, -2 * ((A - 1) + (A + 1) * cs), (A + 1) + (A - 1) * cs - sa);
        }
    }

    /** add a decaying sine partial (rotating phasor, cheap); starts at phase ph0 */
    static void partial(float[] o, double f, double amp, double dec, double ph0, int start) {
        if (f >= SR * 0.45 || amp <= 0) return;
        double w = TAU * f / SR, c = Math.cos(w), s = Math.sin(w), x = Math.cos(ph0), y = Math.sin(ph0);
        double e = amp, de = Math.exp(-1.0 / (dec * SR));
        for (int i = start; i < o.length; i++) {
            o[i] += (float) (y * e);
            double nx = x * c - y * s; y = x * s + y * c; x = nx;
            e *= de;
            if (e < 1e-7) break;
        }
    }

    // =====================================================================================  instruments
    /** string section. art: 0 spiccato, 1 legato, 2 tremolo, 3 marcato, 4 flautando/harmonic */
    static float[][] strings(Random rn, double f, double dur, double vel, int art, double attO, double relO) {
        double att, rel;
        switch (art) {
            case 0: att = 0.005; rel = 0.07; break;
            case 2: att = 0.12; rel = 0.3; break;
            case 3: att = 0.012; rel = 0.2; break;
            case 4: att = 0.5; rel = 0.8; break;
            default: att = 0.1 + 0.22 * (1 - vel); rel = 0.45;
        }
        if (attO > 0) att = attO;
        if (relO > 0) rel = relO;
        int len = (int) ((dur + rel + 0.01) * SR);
        int nv = art == 0 ? 4 : 6;
        double spread = art == 0 ? 6 : 10;
        float[] l = new float[len], r = new float[len];
        for (int v = 0; v < nv; v++) {
            double c = ((v - (nv - 1) / 2.0) / ((nv - 1) / 2.0)) * spread + rn.nextGaussian() * 1.5;
            double fv = f * Math.pow(2, c / 1200.0), ph = rn.nextDouble();
            double pan = ((v & 1) == 0 ? -1 : 1) * (0.25 + 0.5 * rn.nextDouble());
            double gl = Math.sqrt(0.5 * (1 - pan)), gr = Math.sqrt(0.5 * (1 + pan));
            double vibR = 4.8 + 1.4 * rn.nextDouble(), vibP = rn.nextDouble() * TAU;
            double drR = 0.15 + 0.3 * rn.nextDouble(), drP = rn.nextDouble() * TAU;
            double trR = 10 + 6 * rn.nextDouble(), trP = rn.nextDouble() * TAU;
            double vibD = (art == 1 || art == 4) ? 0.0042 : (art == 3 ? 0.002 : 0.0);
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double fm = fv * (1 + vibD * smooth((t - 0.2) / 0.5) * Math.sin(TAU * vibR * t + vibP) + 0.0011 * Math.sin(TAU * drR * t + drP));
                double dt = fm / SR;
                ph += dt; if (ph >= 1) ph -= 1;
                double s = 2 * ph - 1 - blep(ph, dt);
                if (art == 2) s *= 0.55 + 0.45 * Math.sin(TAU * trR * t + trP);
                l[i] += (float) (s * gl); r[i] += (float) (s * gr);
            }
        }
        double fc0 = Math.min(7500, f * (3 + 6 * vel) + 500 * vel);
        if (art == 4) fc0 = Math.min(fc0, f * 2.0 + 200);
        SVF a1 = new SVF(), a2 = new SVF(), b1 = new SVF(), b2 = new SVF();
        double norm = 0.55 / Math.sqrt(nv), eDur = onEnv(art, dur, att);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            double e = t < dur ? onEnv(art, t, att) : eDur * relShape((t - dur) / rel);
            if ((i & 15) == 0) {
                double br = art == 0 ? 1 + 1.2 * Math.exp(-t / 0.025) : art == 3 ? 1 + 0.8 * Math.exp(-t / 0.06) : 0.7 + 0.3 * smooth(t / (att + 0.1));
                double fc = fc0 * br * (0.35 + 0.65 * Math.min(1, e));
                a1.set(fc, 0.6); a2.set(fc, 0.8); b1.set(fc, 0.6); b2.set(fc, 0.8);
            }
            l[i] = (float) (a2.lp(a1.lp(l[i])) * e * norm);
            r[i] = (float) (b2.lp(b1.lp(r[i])) * e * norm);
        }
        return new float[][]{l, r};
    }
    static double onEnv(int art, double t, double att) {
        double a = smooth(t / att);
        if (art == 0) return a * (0.3 + 0.7 * Math.exp(-t / 0.05));
        if (art == 3) return a * (0.55 + 0.45 * Math.exp(-t / 0.12));
        return a;
    }

    /** brass: 3 detuned saws, pitch scoop, low-pass with a brightness envelope, soft saturation */
    static float[][] brass(Random rn, double f, double dur, double vel, double bright, double attO) {
        double att = attO > 0 ? attO : 0.016 + 0.05 * (1 - vel), rel = 0.16;
        int len = (int) ((dur + rel + 0.01) * SR);
        float[] l = new float[len], r = new float[len];
        int nv = 3;
        for (int v = 0; v < nv; v++) {
            double c = (v - 1) * 4.5 + rn.nextGaussian();
            double fv = f * Math.pow(2, c / 1200.0), ph = rn.nextDouble(), pan = (v - 1) * 0.35;
            double gl = Math.sqrt(0.5 * (1 - pan)), gr = Math.sqrt(0.5 * (1 + pan));
            double vibR = 4.9 + 0.8 * rn.nextDouble(), vibP = rn.nextDouble() * TAU;
            double vibD = dur > 0.7 ? 0.0028 : 0, sc = 0.022 * (0.6 + 0.4 * vel);
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double fm = fv * (1 - sc * Math.exp(-t / 0.03)) * (1 + vibD * smooth((t - 0.35) / 0.4) * Math.sin(TAU * vibR * t + vibP));
                double dt = fm / SR;
                ph += dt; if (ph >= 1) ph -= 1;
                double s = 2 * ph - 1 - blep(ph, dt);
                l[i] += (float) (s * gl); r[i] += (float) (s * gr);
            }
        }
        double fLo = f * 1.2, fPk = Math.min(5000, f * (2.5 + 8 * vel * bright));
        double tau = 0.012 + 0.05 * (1 - vel) + (attO > 0 ? attO * 0.5 : 0);
        SVF a1 = new SVF(), a2 = new SVF(), b1 = new SVF(), b2 = new SVF();
        double norm = 0.75 / Math.sqrt(nv), k = Math.tanh(1.6);
        double eDur = smooth(dur / att) * (1 - 0.12 * (1 - Math.exp(-dur / 0.3)));
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            double e = t < dur ? smooth(t / att) * (1 - 0.12 * (1 - Math.exp(-t / 0.3))) : eDur * relShape((t - dur) / rel);
            if ((i & 15) == 0) {
                double be = (1 - Math.exp(-t / tau)) * (0.72 + 0.28 * Math.exp(-t / 0.25));
                double fc = (fLo + (fPk - fLo) * be) * (0.5 + 0.5 * Math.min(1, e));
                a1.set(fc, 0.62); a2.set(fc, 0.85); b1.set(fc, 0.62); b2.set(fc, 0.85);
            }
            double xl = a2.lp(a1.lp(l[i])) * norm, xr = b2.lp(b1.lp(r[i])) * norm;
            l[i] = (float) (Math.tanh(1.6 * xl) / k * e);
            r[i] = (float) (Math.tanh(1.6 * xr) / k * e);
        }
        return new float[][]{l, r};
    }

    /** formants {Hz, gain, bandwidth}: 0 "ah" low, 1 "oh", 2 "ah" high, 3 "oo" (ghostly) */
    static final double[][][] VOW = {
        {{650, 1.0, 80}, {1080, 0.5, 90}, {2650, 0.16, 120}, {2900, 0.1, 130}},
        {{400, 1.0, 70}, {750, 0.32, 80}, {2400, 0.06, 100}, {2600, 0.05, 120}},
        {{800, 1.0, 80}, {1150, 0.5, 90}, {2900, 0.07, 120}, {3900, 0.03, 130}},
        {{325, 1.0, 55}, {700, 0.12, 70}, {2530, 0.025, 170}, {3500, 0.01, 180}}};

    /** choir: 8 detuned voices with vibrato and breath noise through a formant bank */
    static float[][] choir(Random rn, double f, double dur, double vel, int vow, double att) {
        double rel = 0.7;
        int len = (int) ((dur + rel + 0.02) * SR);
        float[][] src = new float[2][len];
        for (int v = 0; v < 8; v++) {
            double fv = f * Math.pow(2, rn.nextGaussian() * 8 / 1200.0), ph = rn.nextDouble();
            double vibR = 4.6 + 1.2 * rn.nextDouble(), vibP = rn.nextDouble() * TAU;
            double j1 = 0.2 + 0.5 * rn.nextDouble(), j2 = 1.1 + 1.3 * rn.nextDouble(), jp = rn.nextDouble() * TAU;
            float[] s = src[v & 1];
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double fm = fv * (1 + 0.0042 * smooth((t - 0.15) / 0.6) * Math.sin(TAU * vibR * t + vibP)
                        + 0.0015 * Math.sin(TAU * j1 * t + jp) + 0.0008 * Math.sin(TAU * j2 * t));
                double dt = fm / SR;
                ph += dt; if (ph >= 1) ph -= 1;
                s[i] += (float) (2 * ph - 1 - blep(ph, dt));
            }
        }
        double[][] F = VOW[vow];
        Noise nz = new Noise(rn.nextLong());
        float[][] out = new float[2][len];
        double tilt = Math.exp(-TAU * 1600.0 / SR);
        for (int c = 0; c < 2; c++) {
            SVF[] bp = new SVF[F.length];
            for (int k = 0; k < F.length; k++) bp[k] = new SVF().set(F[k][0], F[k][0] / F[k][2]);
            SVF body = new SVF().set(Math.min(f * 2.5, 2000), 0.7);
            double y1 = 0;
            for (int i = 0; i < len; i++) {
                double x = src[c][i] * 0.35 + nz.next() * 0.05;
                y1 = x * (1 - tilt) + y1 * tilt;
                double acc = 0.15 * body.lp(y1);
                for (int k = 0; k < F.length; k++) acc += F[k][1] * bp[k].bp(y1);
                out[c][i] = (float) acc;
            }
        }
        double eDur = smooth(dur / att), p2 = rn.nextDouble() * TAU, ss = 0;
        long cnt = 0;
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            double e = (t < dur ? smooth(t / att) : eDur * relShape((t - dur) / rel)) * (1 + 0.06 * Math.sin(TAU * 0.27 * t + p2));
            out[0][i] *= (float) e; out[1][i] *= (float) e;
            if (t >= att * 0.7 && t < dur) { ss += out[0][i] * out[0][i] + out[1][i] * out[1][i]; cnt++; }
        }
        if (cnt < 200) { ss = 0; cnt = 0; for (int i = 0; i < len; i++) { ss += out[0][i] * out[0][i] + out[1][i] * out[1][i]; cnt++; } }
        double g = 0.2 * vel / Math.max(1e-9, Math.sqrt(ss / (2.0 * cnt)));
        for (int i = 0; i < len; i++) { out[0][i] *= (float) g; out[1][i] *= (float) g; }
        return out;
    }

    /** war drum: pitched sine body with pitch drop + modes, noise skin, soft saturation */
    static float[] drum(Random rn, double f0, double vel, double decay, double skin, double skinFc) {
        int len = (int) ((decay * 6.5 + 0.08) * SR);
        float[] o = new float[len];
        Noise nz = new Noise(rn.nextLong());
        double pf = f0 * (1 + 0.012 * rn.nextGaussian());
        double dec = decay * (0.92 + 0.16 * rn.nextDouble()) * (0.8 + 0.3 * vel);
        SVF lp = new SVF().set(skinFc * (0.45 + 0.55 * vel), 0.7), bp = new SVF().set(pf * 3.2, 1.3);
        double p1 = 0, p2 = 0, p3 = 0, amp = Math.pow(vel, 1.25), k = Math.tanh(1.4);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            double f = pf * (1 + 1.1 * Math.exp(-t / 0.016) + 0.12 * Math.exp(-t / 0.12));
            p1 += f / SR; p2 += f * 1.51 / SR; p3 += f * 2.13 / SR;
            if (p1 > 1) p1 -= 1; if (p2 > 1) p2 -= 1; if (p3 > 1) p3 -= 1;
            double body = Math.sin(TAU * p1) * Math.exp(-t / dec) + 0.22 * Math.sin(TAU * p2) * Math.exp(-t / (dec * 0.35))
                    + 0.08 * Math.sin(TAU * p3) * Math.exp(-t / (dec * 0.2));
            double w = nz.next();
            double sk = skin * (lp.lp(w) * 1.4 * Math.exp(-t / 0.012) + bp.bp(w) * 0.6 * Math.exp(-t / 0.045));
            double x = (body + sk * (0.5 + 0.5 * vel)) * Math.min(1, t / 0.002) * amp;
            o[i] = (float) (Math.tanh(1.4 * x) / k);
        }
        fadeTail(o, 0.03);
        return o;
    }

    static float[] snare(Random rn, double vel) {
        int len = (int) (0.45 * SR);
        float[] o = new float[len];
        Noise nz = new Noise(rn.nextLong());
        SVF bp = new SVF().set(1900, 0.9), lp = new SVF().set(6000, 0.7), hp = new SVF().set(350, 0.7);
        double dec = 0.06 + 0.06 * vel, p = 0, amp = Math.pow(vel, 1.2);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            p += 185 * (1 + 0.25 * Math.exp(-t / 0.01)) / SR;
            double body = Math.sin(TAU * p) * Math.exp(-t / 0.035);
            double w = nz.next();
            double ns = lp.lp(hp.hp(w)) * Math.exp(-t / dec) * 0.8 + bp.bp(w) * Math.exp(-t / (dec * 0.6)) * 0.6;
            o[i] = (float) Math.tanh(1.2 * (0.55 * body + ns) * Math.min(1, t / 0.002) * amp);
        }
        fadeTail(o, 0.05);
        return o;
    }

    /** anvil: inharmonic bar modes in beating pairs + strike click */
    static float[] anvil(Random rn, double f0, double vel, double ds) {
        double[] rt = {1, 2.42, 3.97, 5.18, 6.73, 8.36}, am = {1, .7, .5, .36, .24, .14}, dc = {1.8, 1.1, .75, .5, .32, .2};
        int len = (int) (dc[0] * ds * 5 * SR);
        float[] o = new float[len];
        double f = f0 * (1 + 0.006 * rn.nextGaussian());
        for (int k = 0; k < rt.length; k++) {
            double a = am[k] * Math.pow(vel, 0.5 + 0.25 * k);
            partial(o, f * rt[k] * 0.9987, a * 0.5, dc[k] * ds, rn.nextDouble() * TAU, 0);
            partial(o, f * rt[k] * 1.0013, a * 0.5, dc[k] * ds * 0.9, rn.nextDouble() * TAU, 0);
        }
        Noise nz = new Noise(rn.nextLong());
        SVF hp = new SVF().set(2500, 0.7);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            if (t < 0.03) o[i] += (float) (hp.hp(nz.next()) * 0.3 * vel * Math.exp(-t / 0.003));
            o[i] *= (float) (Math.min(1, t / 0.002) * 0.4 * vel);
        }
        fadeTail(o, 0.08);
        return o;
    }

    /** low industrial metal plate hit */
    static float[] metal(Random rn, double f0, double vel) {
        double[] rt = {1, 1.59, 2.14, 2.30, 2.65, 2.92, 3.16, 3.50, 3.60, 4.15, 4.82, 5.4};
        int len = (int) (3.2 * SR);
        float[] o = new float[len];
        for (int k = 0; k < rt.length; k++)
            partial(o, f0 * rt[k] * (1 + 0.003 * rn.nextGaussian()), Math.pow(vel, 0.5 + 0.1 * k) / (1 + 0.35 * k), 1.3 / (1 + 0.25 * k), rn.nextDouble() * TAU, 0);
        Noise nz = new Noise(rn.nextLong());
        SVF lp = new SVF().set(2500, 0.7);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            double x = o[i] + lp.lp(nz.next()) * 0.8 * Math.exp(-t / 0.025);
            o[i] = (float) (Math.tanh(x * 0.6) * Math.min(1, t / 0.002) * 0.5 * vel);
        }
        fadeTail(o, 0.2);
        return o;
    }

    /** noise cymbal: crash (swell<=0) or reverse swell ending at t = swell */
    static float[][] cymbal(Random rn, double vel, double decay, double swell) {
        double tot = swell > 0 ? swell + 0.03 : decay * 4.5;
        int len = (int) (tot * SR);
        float[][] o = new float[2][len];
        for (int c = 0; c < 2; c++) {
            Noise nz = new Noise(rn.nextLong());
            SVF hp = new SVF().set(800, 0.7), lp = new SVF().set(8000, 0.7);
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double e = swell > 0 ? (t < swell ? Math.pow(t / swell, 2.2) : relShape((t - swell) / 0.025))
                        : Math.min(1, t / 0.002) * (0.55 * Math.exp(-t / decay) + 0.45 * Math.exp(-t / 0.07));
                o[c][i] = (float) (lp.lp(hp.hp(nz.next())) * e * 0.45 * vel);
            }
            if (swell <= 0) fadeTail(o[c], 0.3);
        }
        return o;
    }

    /** distorted sub bass; phase locked to absolute time so re-articulated notes stay coherent */
    static float[] sub(double f, double t0, double dur, double vel, double drive) {
        double rel = 0.06;
        int len = (int) ((dur + rel) * SR);
        float[] o = new float[len];
        double ph = f * t0 - Math.floor(f * t0), k = Math.tanh(drive), eDur = smooth(dur / 0.008);
        SVF a = new SVF().set(230, 0.7), b = new SVF().set(230, 0.7);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            ph += f / SR; if (ph >= 1) ph -= 1;
            double s = Math.tanh(drive * Math.sin(TAU * ph)) / k;
            double e = t < dur ? smooth(t / 0.008) : eDur * relShape((t - dur) / rel);
            o[i] = (float) (b.lp(a.lp(s)) * e * vel);
        }
        return o;
    }

    static float[] boom(Random rn, double vel) {
        int len = (int) (4.5 * SR);
        float[] o = new float[len];
        Noise nz = new Noise(rn.nextLong());
        SVF lp = new SVF().set(220, 0.7);
        double p = 0, k = Math.tanh(1.5);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            p += (30 + 65 * Math.exp(-t / 0.11)) / SR; if (p > 1) p -= 1;
            double x = (Math.sin(TAU * p) * Math.exp(-t / 1.3) + lp.lp(nz.next()) * Math.exp(-t / 0.5) * 0.9) * Math.min(1, t / 0.003) * vel;
            o[i] = (float) (Math.tanh(1.5 * x) / k);
        }
        fadeTail(o, 0.3);
        return o;
    }

    /** heartbeat: lub-dub */
    static float[] heart(Random rn, double vel) {
        int len = (int) (0.85 * SR);
        float[] o = new float[len];
        Noise nz = new Noise(rn.nextLong());
        SVF lp = new SVF().set(160, 0.7);
        double[] t0 = {0, 0.17}, f0 = {52, 62}, am = {1, 0.65}, dc = {0.09, 0.07};
        double[] ph = new double[2];
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR, x = 0;
            for (int j = 0; j < 2; j++) {
                double tt = t - t0[j];
                if (tt < 0) continue;
                ph[j] += f0[j] * (1 + 0.8 * Math.exp(-tt / 0.02)) / SR;
                x += Math.sin(TAU * ph[j]) * am[j] * Math.min(1, tt / 0.004) * Math.exp(-tt / dc[j]);
                if (tt < 0.15) x += lp.lp(nz.next()) * 0.5 * am[j] * Math.exp(-tt / 0.04) * Math.min(1, tt / 0.004);
            }
            o[i] = (float) Math.tanh(1.3 * x * vel);
        }
        fadeTail(o, 0.1);
        return o;
    }

    static float[][] bell(Random rn, double f, double vel, double ds) {
        double[][] P = {{0.5, .5, 9}, {1, 1, 7}, {1.19, .55, 5}, {1.5, .4, 4}, {2, .45, 3.4}, {2.51, .28, 2.6}, {2.66, .22, 2.2},
            {3.01, .18, 1.8}, {4.16, .12, 1.2}, {5.43, .07, .8}, {6.8, .04, .6}};
        int len = (int) (9 * ds * 1.2 * SR);
        float[][] o = new float[2][len];
        Noise nz = new Noise(rn.nextLong());
        for (int c = 0; c < 2; c++) {
            for (int k = 0; k < P.length; k++) {
                double det = 1 + (c == 0 ? -1 : 1) * 0.0007 * (k + 1) / 3.0;
                partial(o[c], f * P[k][0] * det, P[k][1] * Math.pow(vel, k * 0.15), P[k][2] * ds, rn.nextDouble() * TAU, 0);
            }
            SVF lp = new SVF().set(2500, 0.7);
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                if (t < 0.04) o[c][i] += (float) (lp.lp(nz.next()) * 0.25 * Math.exp(-t / 0.004));
                o[c][i] *= (float) (Math.min(1, t / 0.002) * 0.22 * vel);
            }
            fadeTail(o[c], 0.3);
        }
        return o;
    }

    /** soft low piano: inharmonic partials, two-stage decay, two detuned strings, hammer noise */
    static float[][] piano(Random rn, double f, double dur, double vel) {
        double B = 0.00028, base = Math.min(10, 5.0 * Math.sqrt(220 / f)), rel = 0.4;
        double hold = Math.min(dur, base * 2);
        int len = (int) ((hold + rel + 0.05) * SR);
        int H = Math.max(1, Math.min(18, (int) (7000 / f)));
        float[][] o = new float[2][len];
        for (int n = 1; n <= H; n++) {
            double fn = n * f * Math.sqrt(1 + B * n * n);
            double amp = Math.pow(n, -(1.1 + 0.9 * (1 - vel))) * (0.4 + 0.6 * Math.abs(Math.sin(Math.PI * n * 0.13)));
            double d1 = base / (1 + 0.3 * (n - 1));
            for (int c = 0; c < 2; c++) {
                double det = c == 0 ? 0.99965 : 1.00035, ph = rn.nextDouble() * TAU;
                partial(o[c], fn * det, amp * 0.65, d1 * 0.22, ph, 0);
                partial(o[c], fn * det, amp * 0.35, d1, ph, 0);
            }
        }
        Noise nz = new Noise(rn.nextLong());
        for (int c = 0; c < 2; c++) {
            SVF lp = new SVF().set(900 + 1800 * vel, 0.7);
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double x = o[c][i] + (t < 0.05 ? lp.lp(nz.next()) * 0.1 * vel * Math.exp(-t / 0.006) : 0);
                x *= Math.min(1, t / 0.0025) * (t < hold ? 1 : relShape((t - hold) / rel));
                o[c][i] = (float) (x * 0.25 * vel);
            }
        }
        return o;
    }

    /** dark saw drone, slowly moving low-pass, equal-power attack/release */
    static float[][] drone(Random rn, double f, double dur, int nv, double fcLo, double fcHi, double lfo, double att, double rel) {
        int len = (int) (dur * SR);
        float[] l = new float[len], r = new float[len];
        for (int v = 0; v < nv; v++) {
            double c = ((v - (nv - 1) / 2.0) / Math.max(1, (nv - 1) / 2.0)) * 8 + rn.nextGaussian();
            double fv = f * Math.pow(2, c / 1200.0), ph = rn.nextDouble(), pan = ((v & 1) == 0 ? -1 : 1) * 0.5;
            double gl = Math.sqrt(0.5 * (1 - pan)), gr = Math.sqrt(0.5 * (1 + pan));
            double dR = 0.05 + 0.1 * rn.nextDouble(), dP = rn.nextDouble() * TAU;
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                double dt = fv * (1 + 0.002 * Math.sin(TAU * dR * t + dP)) / SR;
                ph += dt; if (ph >= 1) ph -= 1;
                double s = 2 * ph - 1 - blep(ph, dt);
                l[i] += (float) (s * gl); r[i] += (float) (s * gr);
            }
        }
        SVF a1 = new SVF(), a2 = new SVF(), b1 = new SVF(), b2 = new SVF();
        double p = rn.nextDouble() * TAU, norm = 0.6 / Math.sqrt(nv), D = len / (double) SR;
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR;
            if ((i & 31) == 0) {
                double fc = fcLo + (fcHi - fcLo) * (0.5 + 0.5 * Math.sin(TAU * lfo * t + p));
                a1.set(fc, 0.7); a2.set(fc, 0.9); b1.set(fc * 1.05, 0.7); b2.set(fc * 1.05, 0.9);
            }
            double e = t < att ? Math.sin(Math.PI / 2 * t / att) : (t > D - rel ? Math.cos(Math.PI / 2 * clamp((t - (D - rel)) / rel, 0, 1)) : 1);
            l[i] = (float) (a2.lp(a1.lp(l[i])) * e * norm);
            r[i] = (float) (b2.lp(b1.lp(r[i])) * e * norm);
        }
        return new float[][]{l, r};
    }

    /** cave air: slowly sweeping band-passed noise */
    static float[][] wind(Random rn, double dur, double fLo, double fHi) {
        int len = (int) (dur * SR);
        float[][] o = new float[2][len];
        for (int c = 0; c < 2; c++) {
            Noise nz = new Noise(rn.nextLong());
            SVF bp = new SVF();
            double p1 = rn.nextDouble() * TAU, p2 = rn.nextDouble() * TAU, p3 = rn.nextDouble() * TAU;
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR;
                if ((i & 31) == 0) {
                    double m = 0.5 + 0.5 * (0.6 * Math.sin(TAU * 0.021 * t + p1) + 0.4 * Math.sin(TAU * 0.047 * t + p2));
                    bp.set(fLo * Math.pow(fHi / fLo, m), 1.6);
                }
                double e = Math.min(Math.sin(Math.PI / 2 * clamp(t / 5, 0, 1)), Math.sin(Math.PI / 2 * clamp((dur - t) / 5, 0, 1)));
                o[c][i] = (float) (bp.bp(nz.next()) * (0.6 + 0.4 * Math.sin(TAU * 0.033 * t + p3)) * e * 0.5);
            }
        }
        return o;
    }

    /** distant chain rattle: clusters of tiny metallic clinks (all partials start at phase 0: no clicks) */
    static float[][] chains(Random rn, double dur, double vel) {
        int len = (int) ((dur + 0.35) * SR);
        float[][] o = new float[2][len];
        int count = 6 + (int) (dur * 16);
        for (int c = 0; c < count; c++) {
            int st = (int) (dur * Math.pow(rn.nextDouble(), 1.3) * SR);
            double a = (0.25 + 0.75 * rn.nextDouble()) * vel, pan = rn.nextDouble() * 1.2 - 0.6;
            double gl = Math.sqrt(0.5 * (1 - pan)), gr = Math.sqrt(0.5 * (1 + pan));
            for (int p = 0; p < 3; p++) {
                double fp = 1700 + rn.nextDouble() * 3300, dec = 0.012 + 0.04 * rn.nextDouble();
                partial(o[0], fp, a * gl / (p + 1), dec, 0, st);
                partial(o[1], fp * 1.003, a * gr / (p + 1), dec, 0, st);
            }
            double fr = 500 + 400 * rn.nextDouble();
            partial(o[0], fr, a * gl * 0.5, 0.05, 0, st);
            partial(o[1], fr, a * gr * 0.5, 0.05, 0, st);
        }
        for (int c = 0; c < 2; c++) {
            SVF lp = new SVF().set(6000, 0.7);
            for (int i = 0; i < len; i++) o[c][i] = (float) (lp.lp(o[c][i]) * 0.3);
            fadeTail(o[c], 0.1);
        }
        return o;
    }

    /** noise riser with a rising open fifth; ends (25 ms release) at t = dur */
    static float[][] riser(Random rn, double dur) {
        int len = (int) ((dur + 0.03) * SR);
        float[][] o = new float[2][len];
        double q1 = 0, q2 = 0;
        float[] tone = new float[len];
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR, x = Math.min(1, t / dur);
            double e = t < dur ? x * x : relShape((t - dur) / 0.025);
            double f = 147 * Math.pow(4, x);
            q1 += f / SR; q2 += f * 1.5 / SR; q1 -= Math.floor(q1); q2 -= Math.floor(q2);
            tone[i] = (float) ((Math.sin(TAU * q1) + 0.6 * Math.sin(TAU * q2)) * 0.12 * e);
        }
        for (int c = 0; c < 2; c++) {
            Noise nz = new Noise(rn.nextLong());
            SVF bp = new SVF();
            for (int i = 0; i < len; i++) {
                double t = (double) i / SR, x = Math.min(1, t / dur);
                if ((i & 15) == 0) bp.set(200 * Math.pow(20, x), 1.6);
                double e = t < dur ? x * x : relShape((t - dur) / 0.025);
                o[c][i] = (float) (bp.bp(nz.next()) * e * 0.6 + tone[i]);
            }
        }
        return o;
    }

    /**
     * Shepard tone rising one octave per period. Phases come from the closed form phi(u) = fmin*P*2^u/ln2, so the
     * signal is exactly periodic with the period (partial k at t+P == partial k+1 at t); wrapping partials are at -70 dB.
     */
    static float[][] shepard(double period, double fmin, int N) {
        int len = (int) Math.round(period * SR);
        float[] o = new float[len];
        double sig = N / 8.0, ln2 = Math.log(2);
        for (int i = 0; i < len; i++) {
            double t = (double) i / SR, s = 0;
            for (int k = 0; k < N; k++) {
                double u = (k + t / period) % N;
                double amp = Math.exp(-0.5 * Math.pow((u - N / 2.0) / sig, 2));
                if (amp < 1e-4) continue;
                double ph = fmin * period * Math.pow(2, u) / ln2;
                s += amp * Math.sin(TAU * (ph - Math.floor(ph)));
            }
            o[i] = (float) (s * 0.2);
        }
        return new float[][]{o, o.clone()};
    }

    // =====================================================================================  track / score
    interface Ins { void n(double beat, double dur, int midi, double vel); }

    /** mixing desk: per-stem fader (linear) */
    static final Map<String, Double> FADER = new HashMap<>();
    static {
        String[] k = {"odaiko", "taiko", "taikoHi", "boom", "sub", "str_mel", "str_ost", "choir_mel", "brass_mel", "str_pad", "choir", "cymbal", "riser", "shime", "drone", "str_trem", "brass_low"};
        double[] v = {0.55, 0.6, 0.8, 0.7, 0.7, 1.7, 1.3, 1.25, 1.1, 1.4, 1.15, 1.5, 1.6, 1.2, 0.75, 1.3, 1.1};
        for (int i = 0; i < k.length; i++) FADER.put(k[i], v[i]);
    }

    static final class Track {
        final String name; final int n; final boolean loop; final double bpm, target; final int bars;
        final float[] L, R, SL, SRr;
        final Random rnd;
        final TreeMap<String, Double> stems = new TreeMap<>();
        Ch[] H;
        double rt60 = 2.5, size = 1.0, wet = 0.35, pre = 20, lowShelf = -2.0, presence = -0.5, fadeIn = 0, fadeOut = 0;
        int vq = 4; // Vorbis quality; the quiet tension loop uses 6 so the codec leaves no step at the loop seam

        Track(String name, double seconds, boolean loop, double bpm, double target, long seed, int bars) {
            this.name = name; this.n = (int) Math.round(seconds * SR); this.loop = loop; this.bpm = bpm; this.target = target;
            this.bars = bars; this.rnd = new Random(seed);
            L = new float[n]; R = new float[n]; SL = new float[n]; SRr = new float[n];
            H = new Ch[Math.max(1, bars) * 4];
            Arrays.fill(H, ch("Dm"));
        }
        static Track bars(String name, int bars, double bpm, double target, long seed) {
            return new Track(name, bars * 4 * 60.0 / bpm, true, bpm, target, seed, bars);
        }
        double sec(double beat) { return beat * 60.0 / bpm; }

        void mix(String stem, double t0, float[] l, float[] r, double gain, double pan, double send) {
            gain *= FADER.getOrDefault(stem, 1.0);
            double a = (clamp(pan, -1, 1) + 1) * Math.PI / 4;
            double gl = Math.cos(a) * Math.sqrt(2) * gain, gr = Math.sin(a) * Math.sqrt(2) * gain, e = 0;
            long i0 = Math.round(t0 * SR);
            for (int k = 0; k < l.length; k++) {
                long ii = i0 + k;
                int j;
                if (loop) j = (int) Math.floorMod(ii, (long) n);
                else { if (ii < 0) continue; if (ii >= n) break; j = (int) ii; }
                float vl = (float) (l[k] * gl), vr = (float) (r[k] * gr);
                L[j] += vl; R[j] += vr; SL[j] += vl * send; SRr[j] += vr * send;
                e += vl * vl + vr * vr;
            }
            stems.merge(stem, e, Double::sum);
        }

        void prog(int bar, String s) {
            for (String b : s.trim().split("\\s+")) {
                String[] parts = b.split("\\|");
                for (int q = 0; q < 4; q++) H[bar * 4 + q] = ch(parts[q * parts.length / 4]);
                bar++;
            }
        }

        // ---------------------------------------------------------------- instruments on the beat grid
        void str(String stem, double beat, double durB, int m, double vel, int art, double gain, double pan) {
            double t0 = sec(beat) + rnd.nextGaussian() * (art == 0 ? 0.003 : 0.006);
            float[][] s = strings(rnd, mtof(m), Math.max(0.03, sec(durB)), vel, art, -1, -1);
            mix(stem, t0, s[0], s[1], gain, pan, art == 0 ? 0.22 : 0.32);
        }
        void brs(String stem, double beat, double durB, int m, double vel, double bright, double gain, double pan, double attB) {
            double t0 = sec(beat) + rnd.nextGaussian() * 0.005;
            float[][] s = brass(rnd, mtof(m), Math.max(0.05, sec(durB)), vel, bright, attB > 0 ? sec(attB) : -1);
            mix(stem, t0, s[0], s[1], gain, pan, 0.3);
        }
        void cho(String stem, double beat, double durB, int m, double vel, int vow, double att, double gain, double pan) {
            double t0 = sec(beat) + rnd.nextGaussian() * 0.006;
            float[][] s = choir(rnd, mtof(m), Math.max(0.08, sec(durB)), vel, vow, att);
            mix(stem, t0, s[0], s[1], gain, pan, 0.45);
        }
        Ins strN(String stem, int art, double gain, double pan) { return (b, d, m, v) -> str(stem, b, d, m, v, art, gain, pan); }
        Ins brsN(String stem, double bright, double gain, double pan) { return (b, d, m, v) -> brs(stem, b, d, m, v, bright, gain, pan, -1); }
        Ins choN(String stem, int vow, double att, double gain, double pan) { return (b, d, m, v) -> cho(stem, b, d, m, v, vow, att, gain, pan); }

        void hit(String kind, double beat, double vel, double gain, double pan) {
            double t0 = sec(beat) + rnd.nextGaussian() * 0.002;
            float[] s; double send;
            switch (kind) {
                case "odaiko": s = drum(rnd, 46, vel, 0.75, 0.55, 900); send = 0.25; break;
                case "taiko": s = drum(rnd, 78, vel, 0.36, 0.7, 1500); send = 0.2; break;
                case "taikoHi": s = drum(rnd, 112, vel, 0.28, 0.75, 1800); send = 0.2; break;
                case "tomA": s = drum(rnd, 165, vel, 0.25, 0.8, 2200); send = 0.22; break;
                case "tomB": s = drum(rnd, 130, vel, 0.25, 0.8, 2100); send = 0.22; break;
                case "tomC": s = drum(rnd, 98, vel, 0.27, 0.8, 2000); send = 0.22; break;
                case "shime": s = drum(rnd, 300, vel, 0.07, 1.0, 3000); send = 0.15; break;
                case "snare": {
                    float[] s2 = snare(rnd, vel);
                    mix(kind, t0 + 0.007, s2, s2, gain * 0.8, pan - 0.3, 0.2);
                    s = snare(rnd, vel); send = 0.2; pan += 0.3; break;
                }
                case "anvil": s = anvil(rnd, 640, vel, 0.55); send = 0.3; break;
                case "metal": s = metal(rnd, 118, vel); send = 0.3; break;
                case "boom": s = boom(rnd, vel); send = 0.25; break;
                case "heart": s = heart(rnd, vel); send = 0.35; break;
                default: throw new IllegalArgumentException(kind);
            }
            mix(kind, t0, s, s, gain, pan, send);
        }
        static double velOf(char c) {
            switch (c) { case 'X': return 1.0; case 'x': return 0.78; case 'o': return 0.52; case 'g': return 0.34; default: return 0; }
        }
        void pat(String kind, int bar0, int bar1, String p, double gain, double pan) {
            double st = 4.0 / p.length();
            for (int bar = bar0; bar < bar1; bar++)
                for (int k = 0; k < p.length(); k++) { double v = velOf(p.charAt(k)); if (v > 0) hit(kind, bar * 4 + k * st, v, gain, pan); }
        }
        void fill(int bar, String p, double gain) {
            String[] kinds = {"tomA", "tomB", "tomC"};
            int total = 0, cnt = 0;
            for (char c : p.toCharArray()) if (velOf(c) > 0) total++;
            double st = 4.0 / p.length();
            for (int i = 0; i < p.length(); i++) {
                double v = velOf(p.charAt(i));
                if (v <= 0) continue;
                int ki = Math.min(2, cnt * 3 / Math.max(1, total));
                hit(kinds[ki], bar * 4 + i * st, v, gain, (1 - ki) * 0.4);
                cnt++;
            }
        }
        void cym(double beat, double vel, double gain) {
            float[][] c = cymbal(rnd, vel, 1.4, 0);
            mix("cymbal", sec(beat), c[0], c[1], gain, 0, 0.35);
        }
        void swell(double endBeat, double lenB, double gain) {
            float[][] c = cymbal(rnd, 1, 0, sec(lenB));
            mix("cymbal", sec(endBeat - lenB), c[0], c[1], gain, 0, 0.4);
        }
        void rise(double endBeat, double lenB, double gain) {
            float[][] c = riser(rnd, sec(lenB));
            mix("riser", sec(endBeat - lenB), c[0], c[1], gain, 0, 0.45);
        }
        void subLine(int bar0, int bar1, double gain, double drive, double vel) {
            for (int bar = bar0; bar < bar1; bar++) {
                int q = 0;
                while (q < 4) {
                    Ch c = H[bar * 4 + q];
                    int e = q + 1;
                    while (e < 4 && H[bar * 4 + e] == c) e++;
                    double t0 = sec(bar * 4 + q);
                    float[] s = sub(mtof(rootIn(c, 31)), t0, sec(e - q) - 0.06, vel, drive);
                    mix("sub", t0, s, s, gain, 0, 0.03);
                    q = e;
                }
            }
        }
        void pads(int bar0, int bar1, int lo, int hi, int nv, Ins ins, double vel) {
            int[] prev = null;
            int s = bar0 * 4, end = bar1 * 4;
            while (s < end) {
                Ch c = H[s];
                int e = s + 1;
                while (e < end && H[e] == c) e++;
                int[] v = voice(c, prev, lo, hi, nv);
                prev = v;
                for (int m : v) ins.n(s, e - s + 0.12, m, vel);
                s = e;
            }
        }
        /** chord hits on a 16-step pattern, voiced with voice-leading */
        void hits(int bar0, int bar1, String p, int lo, int hi, int nv, double durB, Ins ins) {
            double st = 4.0 / p.length();
            int[] prev = null;
            for (int bar = bar0; bar < bar1; bar++)
                for (int k = 0; k < p.length(); k++) {
                    double v = velOf(p.charAt(k));
                    if (v <= 0) continue;
                    double b = bar * 4 + k * st;
                    int[] vs = voice(H[(int) b], prev, lo, hi, nv);
                    prev = vs;
                    for (int m : vs) ins.n(b, durB, m, v);
                }
        }
        void mel(double[][] th, double beat0, double scale, int base, double vel, double legato, Ins ins) {
            double b = beat0;
            for (double[] nt : th) {
                double d = nt[1] * scale;
                double acc = Math.abs(b % 4) < 1e-6 ? 1.0 : (Math.abs(b % 1) < 1e-6 ? 0.93 : 0.86);
                ins.n(b, d * legato, base + (int) nt[0], Math.min(1, vel * acc * (d >= 2 ? 1.04 : 1)));
                b += d;
            }
        }
        /** head motif: root, upper neighbour, root, fourth below */
        void head(double beat, int root, double sc, double vel, Ins ins) {
            boolean lead = Math.floorMod(root, 12) == 9;
            ins.n(beat, 0.9 * sc, root, vel);
            ins.n(beat + sc, 0.45 * sc, step(root, 1, lead), vel * 0.85);
            ins.n(beat + 1.5 * sc, 0.45 * sc, root, vel * 0.9);
            ins.n(beat + 2 * sc, 1.85 * sc, root - 5, vel);
        }
        /** Cerberus gallop: DUM-da-da per beat with neighbour tones on beats 3/4 */
        void gallop(int bar0, int bar1, int shift, double v0, double v1, double gain, double pan) {
            int[][] off = {{0, 0, 0}, {0, 0, 0}, {0, 0, 1}, {0, 0, -1}};
            double[] pos = {0, 0.5, 0.75}, dur = {0.42, 0.2, 0.2}, acc = {1.0, 0.62, 0.7};
            for (int bar = bar0; bar < bar1; bar++) {
                double v = v0 + (v1 - v0) * (bar - bar0) / Math.max(1.0, bar1 - bar0 - 1);
                for (int q = 0; q < 4; q++) {
                    Ch c = H[bar * 4 + q];
                    int root = rootIn(c, 38) + shift;
                    boolean lead = c.root == 9 || c.root == 2;
                    for (int k = 0; k < 3; k++) str("str_ost", bar * 4 + q + pos[k], dur[k], step(root, off[q][k], lead), v * acc[k], 0, gain, pan);
                }
            }
        }
        /** Unleashed 16th ostinato, 3+3+2 accents */
        void ost16(int bar0, int bar1, int shift, double vel, double gain, double pan) {
            int[] off = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1};
            boolean[] acc = new boolean[16];
            for (int k : new int[]{0, 3, 6, 8, 11, 14}) acc[k] = true;
            for (int bar = bar0; bar < bar1; bar++)
                for (int k = 0; k < 16; k++) {
                    double b = bar * 4 + k * 0.25;
                    Ch c = H[(int) b];
                    int root = rootIn(c, 38) + shift;
                    str("str_ost", b, 0.22, step(root, off[k], c.root == 9 || c.root == 2), vel * (acc[k] ? 1.0 : 0.58), 0, gain, pan);
                }
        }
        /** Minotaur labyrinth: 7-note cycle in 8ths, so it circles against the bar; restarts every 4 bars */
        void circle(int bar0, int bar1, int base, double vel, double gain, double pan) {
            int[] cyc = {0, 1, 2, 1, 0, -1, -3};
            for (int bar = bar0; bar < bar1; bar++)
                for (int k = 0; k < 8; k++) {
                    int idx = ((bar - bar0) % 4) * 8 + k;
                    double b = bar * 4 + k * 0.5;
                    Ch c = H[(int) b];
                    int root = rootIn(c, base), d = cyc[idx % 7];
                    int m = d == -3 ? root - 5 : step(root, d, c.root == 9 || c.root == 2);
                    str("str_ost", b, 0.42, m, vel * (idx % 7 == 0 ? 1.0 : 0.72), 0, gain, pan);
                }
        }
        /** a seamless pedal for loops: equal-power cross-fade of the note's tail into its own head */
        void strWrap(String stem, int m, double vel, int art, double gain, double pan, double X) {
            double Ls = (double) n / SR;
            float[][] s = strings(rnd, mtof(m), Ls + X + 0.5, vel, art, 0.01, 0.2);
            int xs = (int) (X * SR);
            for (int i = 0; i < s[0].length; i++) {
                double w;
                if (i < xs) w = Math.sin(Math.PI / 2 * i / xs);
                else if (i < n) w = 1;
                else if (i < n + xs) w = Math.cos(Math.PI / 2 * (i - n) / (double) xs);
                else w = 0;
                s[0][i] *= (float) w; s[1][i] *= (float) w;
            }
            mix(stem, 0, s[0], s[1], gain, pan, 0.35);
        }
        int[] chordAt(Ch c, double beat, double dur, int lo, int hi, int nv, int[] prev, Ins ins, double vel) {
            int[] v = voice(c, prev, lo, hi, nv);
            for (int m : v) ins.n(beat, dur, m, vel);
            return v;
        }
    }

    // =====================================================================================  the score
    static Track ambient() {
        Track T = new Track("pit_ambient", 110, false, 60 * 126.0 / 110.0, -20, 101, 0);
        T.rt60 = 5.5; T.size = 1.45; T.wet = 0.5; T.pre = 40; T.fadeIn = 6; T.fadeOut = 10;
        Random r = T.rnd;
        double D = 126;
        float[][] d = drone(r, mtof(26), T.sec(D), 4, 90, 260, 0.021, 8, 9); T.mix("drone", 0, d[0], d[1], 0.5, 0, 0.25);
        d = drone(r, mtof(38), T.sec(D), 4, 140, 420, 0.017, 10, 9); T.mix("drone", 0, d[0], d[1], 0.32, -0.2, 0.3);
        d = drone(r, mtof(45), T.sec(D - 24), 3, 160, 520, 0.027, 14, 12); T.mix("drone", T.sec(12), d[0], d[1], 0.2, 0.25, 0.35);
        d = drone(r, mtof(39), T.sec(34), 3, 130, 380, 0.04, 9, 10); T.mix("drone", T.sec(44), d[0], d[1], 0.15, 0.3, 0.4);
        float[][] w = wind(r, T.sec(D), 220, 900); T.mix("wind", 0, w[0], w[1], 0.2, 0, 0.5);
        for (double t = 14; t < 54; t += 1.25) { double e = Math.sin(Math.PI * (t - 14) / 40); T.hit("heart", t, 0.5 + 0.3 * e, 0.08 + 0.5 * e, -0.1); }
        for (double t = 80; t < 114; t += 1.3) { double e = Math.sin(Math.PI * (t - 80) / 34); T.hit("heart", t, 0.5 + 0.3 * e, 0.08 + 0.5 * e, 0.1); }
        double[] ct = {9, 23.5, 37, 58, 71.5, 88, 101, 116};
        for (int k = 0; k < ct.length; k++) {
            float[][] c = chains(r, 0.4 + 0.5 * r.nextDouble(), 0.6 + 0.4 * r.nextDouble());
            T.mix("chain", T.sec(ct[k]), c[0], c[1], 0.5, (k % 2 == 0 ? -1 : 1) * (0.3 + 0.5 * r.nextDouble()), 0.7);
        }
        String[] chs = {"Dm", "Bb", "Eb", "A5", "Dm", "Gm", "A", "Dm"};
        double[] at = {6, 24, 40, 56, 68, 84, 98, 108};
        int[] prev = null;
        for (int k = 0; k < chs.length; k++) {
            double st = at[k], en = k + 1 < at.length ? at[k + 1] : 121;
            prev = voice(ch(chs[k]), prev, 50, 69, 3);
            for (int m : prev) {
                float[][] s = choir(r, mtof(m), T.sec(en - st) + 2.5, 0.5, k % 2 == 0 ? 3 : 1, 4.0);
                T.mix("choir", T.sec(st), s[0], s[1], 0.32, (m % 3 - 1) * 0.4, 0.75);
            }
        }
        double[][] pn = {{50, 30, 1.6}, {51, 31.6, .8}, {50, 32.4, .8}, {45, 33.2, 4},
            {46, 47, 1.6}, {48, 48.6, .8}, {46, 49.4, .8}, {45, 50.2, 1.6}, {43, 51.8, 4},
            {50, 90, 1.2}, {51, 91.2, .6}, {50, 91.8, .6}, {53, 92.4, 1.2}, {51, 93.6, 1.2}, {50, 94.8, 1.8}, {49, 96.6, .6}, {50, 97.2, 4}};
        for (double[] p : pn) { float[][] s = piano(r, mtof(p[0]), p[2], 0.55); T.mix("piano", T.sec(p[1]), s[0], s[1], 0.6, -0.15, 0.6); }
        double[][] bl = {{62, 63}, {63, 65}, {62, 66}, {57, 67.2}, {50, 112}};
        for (double[] b : bl) { float[][] s = bell(r, mtof(b[0]), b[0] == 50 ? 0.5 : 0.6, 1.0); T.mix("bell", T.sec(b[1]), s[0], s[1], 0.55, 0.2, 0.7); }
        T.hit("boom", 57, 0.6, 0.45, 0); T.hit("boom", 104, 0.55, 0.4, 0);
        return T;
    }

    static Track tension() {
        Track T = new Track("tension", 32, true, 60, -20, 202, 8);
        T.rt60 = 4.2; T.size = 1.25; T.wet = 0.45; T.pre = 30; T.vq = 6;
        T.strWrap("str_pad", 38, 0.6, 1, 0.5, -0.2, 3.0);
        T.strWrap("str_pad", 50, 0.5, 1, 0.32, 0.2, 3.0);
        int[][] cl = {{50, 57}, {50, 51}, {50, 51, 57}, {50, 57, 58}};
        for (int s = 0; s < 4; s++)
            for (int m : cl[s]) {
                float[][] x = strings(T.rnd, mtof(m), 8.5, 0.45, 2, 1.5, 1.5);
                T.mix("str_trem", s * 8 - 0.75, x[0], x[1], 0.3, (m - 54) * 0.08, 0.4);
            }
        float[][] h1 = strings(T.rnd, mtof(81), 7.0, 0.4, 4, 2.5, 2.0); T.mix("str_harm", 16.5, h1[0], h1[1], 0.14, 0.35, 0.6);
        float[][] h2 = strings(T.rnd, mtof(82), 6.5, 0.45, 4, 2.5, 2.5); T.mix("str_harm", 24.5, h2[0], h2[1], 0.16, 0.35, 0.6);
        for (int b = 0; b < 32; b++) T.hit("heart", b + 0.02, 0.5 + (b % 4 == 0 ? 0.12 : 0), 0.55, 0);
        for (int b = 0; b < 32; b += 8) T.hit("odaiko", b, 0.35, 0.4, 0);
        float[][] sh = shepard(32, 30, 8); T.mix("shepard", 0, sh[0], sh[1], 0.12, 0, 0.5);
        for (int m : new int[]{62, 63}) {
            float[][] c = choir(T.rnd, mtof(m), 13, 0.4, 3, 5.0);
            T.mix("choir", 17, c[0], c[1], 0.22, m == 62 ? -0.3 : 0.3, 0.7);
        }
        return T;
    }

    static Track cerberus() {
        Track T = Track.bars("cerberus", 48, 140, -16, 303);
        T.rt60 = 2.3; T.size = 1.0; T.wet = 0.32; T.pre = 18;
        T.prog(0, "Dm Dm Eb Dm Dm Dm Bb A"); T.prog(8, PA); T.prog(12, PB);
        T.prog(16, "Dm Dm Eb Eb Bb Bb A A"); T.prog(24, PA); T.prog(28, "Dm Bb Eb A");
        T.prog(32, PA); T.prog(36, PB); T.prog(40, "Bb Eb Asus|Asus|A|A A"); T.prog(44, "Dm Dm Eb A");
        Ins horns = T.brsN("brass_mel", 0.55, 0.75, -0.15), hornsDark = T.brsN("brass_mel", 0.25, 0.6, -0.15);
        Ins tbn = T.brsN("brass_low", 0.45, 0.45, 0.15);
        Ins vln = T.strN("str_mel", 1, 0.42, 0.3), vlnS = T.strN("str_mel", 3, 0.4, 0.3);
        // ostinato: basses (root) + celli (octave), violas join in the louder sections
        T.gallop(0, 24, 0, .85, .85, .42, .2); T.gallop(0, 24, 12, .85, .85, .5, -.25);
        T.gallop(28, 32, 0, .4, .85, .42, .2); T.gallop(28, 32, 12, .4, .85, .5, -.25);
        T.gallop(32, 48, 0, .9, .9, .42, .2); T.gallop(32, 48, 12, .9, .9, .5, -.25);
        T.gallop(16, 24, 24, .75, .75, .26, .4); T.gallop(32, 44, 24, .8, .8, .26, .4);
        for (int b = 24; b < 28; b++) {
            int r = rootIn(T.H[b * 4], 38);
            T.str("str_trem", b * 4, 4.1, r, .55, 2, .4, .2); T.str("str_trem", b * 4, 4.1, r + 12, .55, 2, .4, -.25);
        }
        T.pads(8, 16, 62, 77, 3, T.strN("str_pad", 1, .15, .3), .5);
        T.pads(16, 24, 69, 84, 3, T.strN("str_pad", 2, .11, .35), .45);
        T.pads(24, 32, 69, 86, 3, T.strN("str_pad", 2, .11, .35), .4);
        T.pads(32, 40, 45, 60, 3, T.brsN("brass_pad", .35, .2, -.1), .55);
        T.pads(40, 44, 69, 88, 3, T.strN("str_pad", 2, .16, .3), .7);
        T.pads(42, 44, 50, 69, 4, T.brsN("brass_pad", .7, .3, 0), .85);
        // B: the hunt theme in horns + trombones
        T.mel(THEME_A, 32, 1, 62, .85, .92, horns); T.mel(THEME_B, 48, 1, 62, .9, .92, horns);
        T.mel(THEME_A, 32, 1, 50, .7, .92, tbn); T.mel(THEME_B, 48, 1, 50, .75, .92, tbn);
        // C: three heads - low, middle, high, then all three
        T.head(64, 50, 1, .8, tbn); T.head(68, 62, 1, .85, horns); T.head(72, 75, 1, .85, vlnS);
        T.head(76, 51, 1, .9, tbn); T.head(76, 63, 1, .9, horns); T.head(76, 75, 1, .9, vlnS);
        T.head(80, 46, 1, .8, tbn); T.head(84, 58, 1, .85, horns); T.head(88, 69, 1, .85, vlnS);
        T.head(92, 45, 1, .95, tbn); T.head(92, 57, 1, .95, horns); T.head(92, 69, 1, .95, vlnS);
        // D: stalking breakdown, then rebuild
        T.mel(THEME_A, 96, 1, 50, .55, .95, hornsDark);
        T.head(112, 62, 1, .6, horns); T.head(116, 58, 1, .68, horns); T.head(120, 63, 1, .76, horns); T.head(124, 57, 1, .85, horns);
        // E: full statement
        T.mel(THEME_A, 128, 1, 74, .9, .95, vln); T.mel(THEME_B, 144, 1, 74, .95, .95, vln);
        T.mel(THEME_A, 128, 1, 62, .9, .92, horns); T.mel(THEME_B, 144, 1, 62, .95, .92, horns);
        // F: lift, G: turnaround
        T.head(160, 58, 1, .9, horns); T.head(160, 70, 1, .85, vlnS); T.head(164, 63, 1, .95, horns); T.head(164, 75, 1, .9, vlnS);
        T.head(176, 50, 1, .8, tbn); T.head(180, 62, 1, .8, horns);
        Ins stab = T.brsN("brass_stab", 1.0, .38, 0);
        for (int b : new int[]{1, 3, 5, 7}) T.hits(b, b + 1, "......x...x.X...", 45, 62, 3, .4, stab);
        T.hits(16, 24, "............X.X.", 45, 62, 3, .4, stab);
        T.hits(44, 48, "X.....x...x.X...", 45, 62, 3, .4, stab);
        // drums
        String TA = "X..x..x.X..x.x..";
        T.pat("taiko", 0, 24, TA, .7, -.1); T.pat("taiko", 32, 48, TA, .7, -.1);
        T.pat("odaiko", 0, 24, "X...............", .8, 0); T.pat("odaiko", 32, 44, "X.......x.......", .8, 0); T.pat("odaiko", 44, 48, "X...............", .8, 0);
        T.pat("snare", 8, 24, "....X.......X...", .42, .1); T.pat("snare", 32, 44, "....X..o....X..o", .42, .1);
        T.pat("shime", 16, 24, "..x...x...x...x.", .25, .4); T.pat("shime", 32, 44, "..x...x...x...x.", .25, .4);
        T.pat("odaiko", 24, 28, "X...............", .6, 0); T.pat("taiko", 24, 28, "X.o.............", .45, 0);
        T.pat("taiko", 28, 30, "X...x...X...x...", .5, -.1); T.pat("taiko", 30, 32, "X.x.X.x.X.x.X.x.", .55, -.1);
        for (int k = 0; k < 32; k++) T.hit("snare", 120 + k * 0.25, 0.25 + 0.7 * k / 31.0, .3, .1);
        T.fill(7, "........x.x.xxXX", .55); T.fill(15, "........x.x.xxXX", .55); T.fill(23, "....x.x.x.xxxxXX", .58);
        T.fill(39, "........x.x.xxXX", .55); T.fill(43, "....x.x.xxxxXXXX", .58); T.fill(47, "x.x.x.x.xxxxXXXX", .6);
        for (double b : new double[]{0, 32, 64, 128, 160}) T.cym(b, .7, .28);
        T.swell(32, 2, .22); T.swell(128, 4, .25); T.swell(192, 4, .25);
        T.rise(128, 8, .22); T.rise(192, 8, .22);
        for (double b : new double[]{0, 64, 128, 160}) T.hit("boom", b, .9, .45, 0);
        T.subLine(0, 24, .3, 2.0, .8); T.subLine(24, 28, .2, 1.5, .6); T.subLine(28, 48, .3, 2.0, .8);
        return T;
    }

    static Track unleashed() {
        Track T = Track.bars("cerberus_unleashed", 52, 156, -16, 404);
        T.rt60 = 2.5; T.size = 1.05; T.wet = 0.33; T.pre = 18;
        T.prog(0, "Dm Dm Eb A"); T.prog(4, "Dm Eb Dm Eb Bb C A5 A");
        T.prog(12, PAD); T.prog(14, PBD); T.prog(16, PAD); T.prog(18, PBD);
        T.prog(20, PA); T.prog(24, PB); T.prog(28, "Dm Dm Eb Eb Gm Gm A5 A");
        T.prog(36, PA); T.prog(40, PB); T.prog(44, "Bb Eb A5 A"); T.prog(48, "Dm Dm Eb A");
        Ins horns = T.brsN("brass_mel", 0.6, 0.62, -0.15), tpt = T.brsN("brass_hi", 0.75, 0.38, 0.1);
        Ins tbn = T.brsN("brass_low", 0.5, 0.45, 0.15);
        Ins vln = T.strN("str_mel", 1, 0.4, 0.3), vlnS = T.strN("str_mel", 3, 0.38, 0.3);
        Ins choirMel = T.choN("choir_mel", 2, 0.07, 0.95, 0);
        // ostinato
        T.ost16(0, 28, 0, .85, .38, .2); T.ost16(0, 28, 12, .85, .45, -.25);
        T.ost16(36, 52, 0, .9, .38, .2); T.ost16(36, 52, 12, .9, .45, -.25);
        T.ost16(4, 28, 24, .7, .22, .4); T.ost16(36, 48, 24, .75, .22, .4);
        // intro shout
        T.pads(0, 2, 50, 74, 4, T.choN("choir", 0, .08, .75, 0), 1.0);
        T.hits(0, 1, "X...............", 45, 62, 3, 3.5, T.brsN("brass_stab", 1.0, .4, 0));
        // pads / choir
        T.pads(4, 12, 57, 72, 3, T.choN("choir", 0, .4, .5, 0), .7);
        T.pads(12, 20, 62, 77, 3, T.choN("choir", 2, .3, .42, 0), .65);
        T.pads(20, 28, 69, 86, 3, T.strN("str_pad", 2, .13, .35), .55);
        T.pads(28, 36, 45, 60, 3, T.choN("choir", 1, .6, .6, 0), .7);
        T.pads(36, 44, 69, 86, 3, T.strN("str_pad", 2, .14, .35), .6);
        T.pads(44, 48, 62, 79, 4, T.choN("choir", 2, .3, .55, 0), .85);
        T.pads(46, 48, 50, 69, 4, T.brsN("brass_pad", .7, .3, 0), .85);
        // B: diminution, higher and faster
        for (int k = 0; k < 2; k++) {
            double b = 48 + k * 16;
            T.mel(THEME_A, b, .5, 74, .9, .9, vln); T.mel(THEME_B, b + 8, .5, 74, .92, .9, vln);
            T.mel(THEME_A, b, .5, 62, .85, .88, horns); T.mel(THEME_B, b + 8, .5, 62, .88, .88, horns);
            if (k == 1) { T.mel(THEME_A, b, .5, 74, .85, .85, tpt); T.mel(THEME_B, b + 8, .5, 74, .9, .85, tpt); }
        }
        // C: choir sings the broad theme, three heads answer in the strings
        T.mel(THEME_A, 80, 1, 62, .9, .98, choirMel); T.mel(THEME_B, 96, 1, 62, .95, .98, choirMel);
        T.mel(THEME_A, 80, 1, 50, .7, .95, tbn); T.mel(THEME_B, 96, 1, 50, .75, .95, tbn);
        T.hits(20, 28, "..........x.X...", 45, 62, 3, .35, T.brsN("brass_stab", 1.0, .32, 0));
        // D: burning breakdown - fire-breath swells in the low brass
        for (int b = 28; b < 36; b += 2) {
            int[] v = voice(T.H[b * 4], null, 41, 58, 4);
            for (int m : v) T.brs("brass_swell", b * 4, 7.6, m, .9, .8, .3, (m - 50) * 0.04, 6.5);
        }
        for (int b = 28; b < 36; b++) { int r = rootIn(T.H[b * 4], 38); T.str("str_low", b * 4, 1.5, r, .8, 3, .45, .1); T.str("str_low", b * 4 + 2, 1.5, r + 12, .7, 3, .4, -.1); }
        T.head(128, 62, 1, .7, horns); T.head(136, 63, 1, .75, horns); T.head(128 + 16, 55, 1, .8, horns); T.head(128 + 24, 57, 1, .9, horns);
        // E: climax
        T.mel(THEME_A, 144, 1, 62, .95, .98, choirMel); T.mel(THEME_B, 160, 1, 62, 1, .98, choirMel);
        T.mel(THEME_A, 144, 1, 74, .95, .95, vln); T.mel(THEME_B, 160, 1, 74, 1, .95, vln);
        T.mel(THEME_A, 144, 1, 74, .9, .9, tpt); T.mel(THEME_B, 160, 1, 74, .95, .9, tpt);
        T.mel(THEME_A, 144, 1, 62, .9, .92, horns); T.mel(THEME_B, 160, 1, 62, .95, .92, horns);
        T.mel(THEME_A, 144, 1, 50, .85, .92, tbn); T.mel(THEME_B, 160, 1, 50, .9, .92, tbn);
        T.head(176, 58, 1, .9, horns); T.head(176, 70, 1, .85, vlnS); T.head(180, 63, 1, .95, horns); T.head(180, 75, 1, .9, vlnS);
        // F: transition back
        T.head(192, 50, 1, .85, tbn); T.head(196, 62, 1, .85, horns);
        T.hits(48, 52, "X.....x...x.X...", 45, 62, 3, .4, T.brsN("brass_stab", 1.0, .38, 0));
        // drums
        String TA = "X..x..x.X..x..x.", TH = "..o..o.o..o..o.o";
        T.pat("taiko", 0, 28, TA, .68, -.1); T.pat("taiko", 36, 52, TA, .7, -.1);
        T.pat("taikoHi", 4, 28, TH, .45, .3); T.pat("taikoHi", 36, 52, TH, .48, .3);
        T.pat("odaiko", 0, 12, "X.......X.......", .8, 0); T.pat("odaiko", 12, 28, "X...............", .8, 0); T.pat("odaiko", 36, 52, "X.......X.......", .82, 0);
        T.pat("snare", 4, 28, "....X.......X...", .42, .1); T.pat("snare", 36, 52, "....X.......X..o", .44, .1);
        T.pat("shime", 36, 48, "x.x.x.x.x.x.x.x.", .2, .45); T.pat("tomB", 36, 44, "....x.......x.x.", .4, -.3);
        T.pat("metal", 36, 44, "X...............", .28, 0);
        T.pat("odaiko", 28, 36, "X...............", .8, 0); T.pat("taiko", 28, 36, "........X.....x.", .6, -.1); T.pat("metal", 28, 36, "........X.......", .25, .2);
        T.fill(3, "....x.x.xxxxXXXX", .55); T.fill(11, "........x.x.xxXX", .55); T.fill(19, "........xxxxXXXX", .55); T.fill(27, "....x.x.xxxxXXXX", .58);
        T.fill(35, "xxxxxxxxXXXXXXXX", .6); T.fill(43, "........x.x.xxXX", .55); T.fill(47, "....x.x.xxxxXXXX", .58); T.fill(51, "x.x.x.x.xxxxXXXX", .6);
        for (double b : new double[]{0, 16, 48, 80, 144, 176}) T.cym(b, .75, .28);
        T.swell(16, 2, .2); T.swell(48, 4, .24); T.swell(144, 4, .26); T.swell(208, 4, .25);
        T.rise(144, 8, .25); T.rise(208, 8, .22);
        for (double b : new double[]{0, 48, 80, 144}) T.hit("boom", b, .9, .45, 0);
        T.subLine(0, 28, .3, 2.2, .8); T.subLine(28, 36, .32, 2.6, .85); T.subLine(36, 52, .3, 2.2, .8);
        return T;
    }

    static Track minotaur() {
        Track T = Track.bars("minotaur", 36, 100, -16, 505);
        T.rt60 = 2.9; T.size = 1.15; T.wet = 0.34; T.pre = 24;
        T.prog(0, "Dm Dm Eb A"); T.prog(4, PAA); T.prog(12, "Dm Eb Dm Eb Bb Cm A5 A"); T.prog(20, "Dm Dm Eb Eb Gm Gm A5 A");
        T.prog(28, PA); T.prog(32, PB);
        Ins tbn = T.brsN("brass_mel", .5, .7, .1), tuba = T.brsN("brass_low", .35, .55, 0), horns = T.brsN("brass_mel", .5, .45, -.2);
        Ins hornsC = T.brsN("brass_ctr", .4, .45, -.3);
        // labyrinth ostinato
        T.circle(0, 36, 48, .85, .5, -.2);
        T.circle(0, 12, 36, .8, .35, .2); T.circle(20, 36, 36, .85, .38, .2);
        T.circle(12, 20, 60, .75, .32, .35); T.circle(12, 20, 72, .7, .2, .45);
        T.circle(20, 36, 60, .75, .26, .35);
        T.str("str_trem", 0, 16, 38, .6, 2, .3, 0);
        // A: augmented theme, broad, in the low brass
        T.mel(THEME_A, 16, 2, 50, .9, .95, tbn); T.mel(THEME_A, 16, 2, 38, .85, .95, tuba); T.mel(THEME_A, 16, 2, 62, .7, .95, horns);
        // B: horn counter line + male choir
        int[] cm = {69, 70, 69, 67, 65, 63, 62, 61};
        for (int k = 0; k < 8; k++) hornsC.n(48 + 4 * k, 4.05, cm[k], .72);
        T.pads(12, 20, 45, 62, 3, T.choN("choir", 1, .6, .7, 0), .6);
        // C: "faster with every hit" - head motif alternating trombones / horns, growing
        int[] hr = {50, 62, 51, 63, 55, 67, 57, 69};
        Ins vlnS = T.strN("str_mel", 3, .4, .3);
        for (int k = 0; k < 8; k++) {
            double v = .6 + .4 * k / 7.0;
            T.head(80 + 4 * k, hr[k], 1, v, k % 2 == 0 ? tbn : horns);
            if (k >= 6) T.head(80 + 4 * k, hr[k] + 12, 1, v, vlnS);
        }
        T.pads(20, 28, 45, 62, 3, T.choN("choir", 1, .5, .55, 0), .6);
        // D: the theme at full weight, choir doubling
        Ins choirMel = T.choN("choir_mel", 0, .07, .9, 0);
        T.mel(THEME_A, 112, 1, 50, .95, .95, tbn); T.mel(THEME_B, 128, 1, 50, 1, .95, tbn);
        T.mel(THEME_A, 112, 1, 38, .9, .95, tuba); T.mel(THEME_B, 128, 1, 38, .95, .95, tuba);
        T.mel(THEME_A, 112, 1, 62, .85, .95, horns); T.mel(THEME_B, 128, 1, 62, .9, .95, horns);
        T.mel(THEME_A, 112, 1, 62, .85, .98, choirMel); T.mel(THEME_B, 128, 1, 62, .9, .98, choirMel);
        T.pads(28, 36, 69, 86, 3, T.strN("str_pad", 2, .14, .3), .6);
        // drums
        T.pat("odaiko", 0, 20, "X.......X.....x.", .85, 0); T.pat("odaiko", 28, 36, "X.......X.....x.", .85, 0);
        T.pat("anvil", 0, 4, "............X...", .34, .3); T.pat("anvil", 4, 20, "....X.......X...", .3, .3); T.pat("anvil", 28, 36, "....X.......X...", .32, .3);
        T.pat("taiko", 4, 20, ".......x..x....x", .6, -.2); T.pat("taiko", 28, 36, "...x...x..x..x.x", .62, -.2);
        T.pat("snare", 28, 36, "....X.......X...", .4, .1);
        for (int b = 4; b < 20; b += 2) T.hit("metal", b * 4, .8, .28, 0);
        for (int b = 28; b < 36; b += 2) T.hit("metal", b * 4, .85, .3, 0);
        T.pat("odaiko", 20, 28, "X...............", .85, 0);
        T.pat("taiko", 20, 22, "X...X...X...X...", .6, -.15);
        T.pat("taiko", 22, 24, "X.x.X.x.X.x.X.x.", .6, -.15);
        T.pat("taiko", 24, 26, "XxxXxxXxxXxx", .6, -.15);
        T.pat("taiko", 26, 28, "XxoxXxoxXxoxXxox", .6, -.15);
        T.pat("tomB", 26, 28, "..x...x...x...x.", .38, .3);
        T.pat("anvil", 20, 26, "....X.......X...", .3, .3); T.pat("anvil", 26, 28, "X...X...X...X...", .28, .3);
        T.fill(3, "........x.x.xxXX", .6); T.fill(11, "....x.x.xxxxXXXX", .6); T.fill(19, "........xxxxXXXX", .6);
        T.fill(27, "xxxxxxxxXXXXXXXX", .62); T.fill(35, "........x.x.xxXX", .6);
        for (double b : new double[]{0, 16, 48, 80, 112}) T.cym(b, .7, .28);
        T.swell(16, 4, .25); T.swell(112, 4, .28); T.swell(144, 2, .2);
        T.rise(112, 8, .24);
        for (double b : new double[]{0, 16, 48, 80, 112}) T.hit("boom", b, .95, .5, 0);
        T.subLine(0, 36, .32, 2.8, .85);
        return T;
    }

    static Track fin() {
        Track T = Track.bars("minotaur_final", 44, 120, -16, 606);
        T.rt60 = 2.7; T.size = 1.1; T.wet = 0.33; T.pre = 22;
        T.prog(0, "Dm Dm Eb A"); T.prog(4, PAA); T.prog(12, PA); T.prog(16, PB); T.prog(20, "Dm Dm Eb A");
        T.prog(24, PAA); T.prog(32, PA); T.prog(36, PB); T.prog(40, "Dm Eb Dm A");
        Ins tbn = T.brsN("brass_mel", .5, .65, .1), tuba = T.brsN("brass_low", .35, .5, 0), horns = T.brsN("brass_mel", .55, .5, -.2);
        Ins tpt = T.brsN("brass_hi", .75, .36, .1);
        Ins vln = T.strN("str_mel", 1, .4, .3), vlnS = T.strN("str_mel", 3, .38, .3);
        Ins choirMel = T.choN("choir_mel", 2, .07, .95, 0);
        // intro: tutti hits
        String IP = "X..X..X...X.X...";
        T.hits(0, 3, IP, 45, 69, 4, .45, T.brsN("brass_stab", 1.0, .36, 0));
        T.hits(0, 3, IP, 50, 74, 4, .45, T.choN("choir", 0, .02, .45, 0));
        T.hits(0, 3, IP, 38, 62, 3, .45, T.strN("str_low", 3, .35, 0));
        T.pat("odaiko", 0, 3, IP, .8, 0); T.pat("anvil", 0, 3, "X.....X.........", .3, .3);
        T.fill(3, "....x.x.xxxxXXXX", .6); T.rise(16, 4, .22);
        // both ostinati: Cerberus' gallop (celli/basses) and the Minotaur's circle (violas)
        T.gallop(4, 20, 0, .85, .85, .38, .2); T.gallop(4, 20, 12, .85, .85, .45, -.25);
        T.gallop(24, 44, 0, .9, .9, .38, .2); T.gallop(24, 44, 12, .9, .9, .45, -.25);
        T.circle(4, 20, 60, .75, .28, .4); T.circle(24, 40, 60, .78, .28, .4);
        // A: augmented theme in the low brass
        T.mel(THEME_A, 16, 2, 50, .95, .95, tbn); T.mel(THEME_A, 16, 2, 38, .9, .95, tuba); T.mel(THEME_A, 16, 2, 62, .75, .95, horns);
        T.pads(4, 12, 50, 65, 3, T.choN("choir", 1, .5, .55, 0), .6);
        T.pads(4, 12, 69, 86, 3, T.strN("str_pad", 2, .12, .3), .55);
        // B: choir + violins sing the theme
        T.mel(THEME_A, 48, 1, 62, .9, .98, choirMel); T.mel(THEME_B, 64, 1, 62, .95, .98, choirMel);
        T.mel(THEME_A, 48, 1, 74, .9, .95, vln); T.mel(THEME_B, 64, 1, 74, .95, .95, vln);
        T.pads(12, 20, 45, 60, 3, T.brsN("brass_pad", .4, .22, 0), .6);
        // C: frenzy - he swallows the hellfire
        T.pads(20, 24, 57, 74, 4, T.choN("choir", 0, .4, .7, 0), .85);
        for (int b = 20; b < 24; b++) { int r = rootIn(T.H[b * 4], 38); T.str("str_trem", b * 4, 4.1, r, .7, 2, .4, .2); T.str("str_trem", b * 4, 4.1, r + 12, .7, 2, .4, -.2); }
        T.rise(96, 8, .28);
        // D: augmented theme in the bass against the head motif sequence above
        T.mel(THEME_A, 96, 2, 50, .95, .95, tbn); T.mel(THEME_A, 96, 2, 38, .9, .95, tuba);
        int[] hr = {62, 74, 67, 79, 70, 82, 69, 81};
        for (int k = 0; k < 8; k++) T.head(96 + 4 * k, hr[k], 1, .8 + .02 * k, k % 2 == 0 ? horns : vlnS);
        T.pads(24, 32, 57, 72, 3, T.choN("choir", 0, .4, .5, 0), .7);
        // E: grand statement, tutti
        T.mel(THEME_A, 128, 1, 62, .95, .98, choirMel); T.mel(THEME_B, 144, 1, 62, 1, .98, choirMel);
        T.mel(THEME_A, 128, 1, 74, .95, .95, vln); T.mel(THEME_B, 144, 1, 74, 1, .95, vln);
        T.mel(THEME_A, 128, 1, 74, .9, .9, tpt); T.mel(THEME_B, 144, 1, 74, .95, .9, tpt);
        T.mel(THEME_A, 128, 1, 62, .9, .92, horns); T.mel(THEME_B, 144, 1, 62, .95, .92, horns);
        T.mel(THEME_A, 128, 1, 50, .9, .92, tbn); T.mel(THEME_B, 144, 1, 50, .95, .92, tbn);
        T.pads(32, 40, 33, 45, 2, T.brsN("brass_low", .3, .3, 0), .7);
        // F: tail back into the intro
        T.hits(40, 44, "X.....x...x.X...", 45, 62, 3, .4, T.brsN("brass_stab", 1.0, .38, 0));
        T.head(160, 50, 1, .85, tbn); T.head(168, 50, 1, .9, tbn);
        T.rise(176, 8, .24);
        // drums
        String TA = "X..x..x.X..x.x..";
        T.pat("odaiko", 4, 20, "X.......X.......", .82, 0); T.pat("odaiko", 24, 44, "X.......X.......", .82, 0);
        T.pat("taiko", 4, 20, TA, .66, -.15); T.pat("taiko", 24, 44, TA, .68, -.15);
        T.pat("anvil", 4, 20, "....X.......X...", .3, .3); T.pat("anvil", 24, 44, "....X.......X...", .3, .3);
        T.pat("snare", 4, 20, "....X.......X...", .4, .1); T.pat("snare", 24, 44, "....X.......X..o", .42, .1);
        T.pat("shime", 12, 20, "..x...x...x...x.", .22, .45); T.pat("shime", 32, 40, "..x...x...x...x.", .22, .45);
        T.pat("taiko", 20, 24, "XxoxXxoxXxoxXxox", .6, -.1); T.pat("odaiko", 20, 24, "X.......X.......", .82, 0);
        T.pat("anvil", 20, 24, "X...X...X...X...", .28, .3);
        for (int b : new int[]{4, 8, 12, 16, 24, 28, 32, 36}) T.hit("metal", b * 4, .85, .3, 0);
        T.fill(11, "........x.x.xxXX", .55); T.fill(19, "....x.x.xxxxXXXX", .58); T.fill(23, "xxxxxxxxXXXXXXXX", .62);
        T.fill(31, "........x.x.xxXX", .55); T.fill(39, "....x.x.xxxxXXXX", .58); T.fill(43, "x.x.x.x.xxxxXXXX", .6);
        for (double b : new double[]{0, 16, 48, 96, 128}) T.cym(b, .75, .28);
        T.swell(48, 4, .24); T.swell(96, 2, .22); T.swell(128, 4, .26); T.swell(176, 4, .25);
        for (double b : new double[]{0, 16, 96, 128}) T.hit("boom", b, .95, .5, 0);
        T.subLine(0, 44, .3, 2.6, .85);
        return T;
    }

    static Track victory() {
        Track T = new Track("victory", 15.5, false, 60, -16, 707, 0);
        T.rt60 = 4.2; T.size = 1.3; T.wet = 0.45; T.pre = 30; T.fadeOut = 1.8;
        double t0 = 0.04;
        T.hit("boom", t0, 1.0, 0.6, 0); T.hit("odaiko", t0, 1.0, 0.8, 0); T.hit("metal", t0, 0.8, 0.35, 0); T.cym(t0, 0.8, 0.35);
        Object[][] seg = {{"Dm", 0.0, 3.0}, {"Bb", 3.0, 2.0}, {"Eb", 5.0, 2.0}, {"Asus", 7.0, 0.75}, {"A", 7.75, 0.75}, {"D", 8.5, 4.5}};
        int[] ps = null, pc = null, pb = null;
        for (Object[] s : seg) {
            Ch c = ch((String) s[0]);
            double b = t0 + (double) s[1], d = (double) s[2] + 0.15;
            boolean fin = s[0].equals("D");
            double v = fin ? 0.9 : 0.62;
            ps = T.chordAt(c, b, d, 50, 74, 4, ps, T.strN("str_pad", 1, .22, .2), v);
            pc = T.chordAt(c, b, d, 50, 69, 3, pc, T.choN("choir", 0, fin ? .25 : .4, .6, 0), v);
            pb = T.chordAt(c, b, d, 41, 57, 3, pb, T.brsN("brass_pad", fin ? .7 : .35, .25, -.1), v);
            int r = rootIn(c, 38);
            T.str("str_low", b, d, r, v, 1, .4, 0);
            float[] sb = sub(mtof(rootIn(c, 31)), T.sec(b), T.sec(d) - 0.06, v, 1.8);
            T.mix("sub", T.sec(b), sb, sb, .3, 0, 0.03);
            if (fin) T.chordAt(c, b, d, 66, 78, 3, null, T.choN("choir", 2, .3, .5, 0), .85);
        }
        Ins horns = T.brsN("brass_mel", .55, .7, -.15), tpt = T.brsN("brass_hi", .7, .36, .15);
        double[][] mel = {{62, 0, 1}, {63, 1, .5}, {62, 1.5, .5}, {57, 2, 1}, {58, 3, 1}, {60, 4, .5}, {62, 4.5, .5},
            {67, 5, 1}, {65, 6, .5}, {63, 6.5, .5}, {62, 7, .75}, {61, 7.75, .75}};
        for (double[] m : mel) horns.n(t0 + m[1], m[2] * 0.95, (int) m[0], .75);
        double[][] fin = {{62, 8.5, 1}, {64, 9.5, .5}, {62, 10, .5}, {69, 10.5, 2.6}};
        for (double[] m : fin) { horns.n(t0 + m[1], m[2] * 0.97, (int) m[0], .92); tpt.n(t0 + m[1], m[2] * 0.97, (int) m[0] + 12, .85); }
        for (int k = 0; k < 6; k++) T.hit("taiko", t0 + 7 + k * 0.25, 0.3 + 0.12 * k, 0.5, 0);
        T.hit("odaiko", t0 + 8.5, 1.0, 0.8, 0); T.hit("boom", t0 + 8.5, 0.9, 0.5, 0); T.cym(t0 + 8.5, 0.8, 0.3);
        float[][] b1 = bell(T.rnd, mtof(62), 0.7, 0.8); T.mix("bell", T.sec(t0 + 8.5), b1[0], b1[1], 0.5, -0.2, 0.6);
        float[][] b2 = bell(T.rnd, mtof(74), 0.6, 0.6); T.mix("bell", T.sec(t0 + 10.5), b2[0], b2[1], 0.35, 0.25, 0.6);
        return T;
    }

    // =====================================================================================  mixing / mastering
    static void reverb(Track T) {
        int n = T.n;
        boolean loop = T.loop;
        float[] inL = T.SL, inR = T.SRr, oL = new float[n], oR = new float[n];
        double size = T.size;
        Bq hL = Bq.hp(160, 0.7), hR = Bq.hp(160, 0.7);
        SVF lL = new SVF().set(6500, 0.7), lR = new SVF().set(6500, 0.7);
        int pre = (int) (T.pre * SR / 1000.0);
        double[] tapMs = {7.1, 11.3, 16.9, 23.4, 29.7, 37.3, 44.9, 53.2, 61.7, 71.9};
        double[] tapG = {.62, .5, .45, .4, .34, .3, .26, .22, .18, .15};
        int nt = tapMs.length, maxD = pre;
        int[] tapD = new int[nt];
        for (int k = 0; k < nt; k++) { tapD[k] = pre + (int) (tapMs[k] * size * SR / 1000); maxD = Math.max(maxD, tapD[k]); }
        int P = maxD + 2, w = 0;
        double[] rL = new double[P], rR = new double[P];
        int[] apLenL = {142, 107, 379, 277}, apLenR = {151, 113, 389, 263};
        double[][] apL = new double[4][], apR = new double[4][];
        int[] apPL = new int[4], apPR = new int[4];
        for (int k = 0; k < 4; k++) { apL[k] = new double[(int) (apLenL[k] * Math.sqrt(size))]; apR[k] = new double[(int) (apLenR[k] * Math.sqrt(size))]; }
        double apg = 0.6;
        int[] base = {1433, 1601, 1867, 2053, 2251, 2399, 2617, 2797};
        int N = 8;
        double[][] dl = new double[N][];
        int[] dp = new int[N];
        double[] g = new double[N], lp = new double[N], o = new double[N];
        for (int j = 0; j < N; j++) { int len = (int) (base[j] * size); dl[j] = new double[len]; g[j] = Math.pow(10, -3.0 * len / (T.rt60 * SR)); }
        double damp = 0.35;
        double[] sL = {1, -1, 1, -1, 1, -1, 1, -1}, sRr = {1, 1, -1, -1, 1, 1, -1, -1}, inS = {1, 1, -1, 1, -1, -1, 1, -1};
        int total = loop ? 2 * n : n;
        for (int ii = 0; ii < total; ii++) {
            int i = ii % n;
            double xl = lL.lp(hL.p(inL[i])), xr = lR.lp(hR.p(inR[i]));
            rL[w] = xl; rR[w] = xr;
            int rp = w - pre; if (rp < 0) rp += P;
            double pl = rL[rp], pr = rR[rp], el = 0, er = 0;
            for (int k = 0; k < nt; k++) {
                int q = w - tapD[k]; if (q < 0) q += P;
                if ((k & 1) == 0) { el += rL[q] * tapG[k]; er += (rR[q] * 0.6 + rL[q] * 0.2) * tapG[k]; }
                else { er += rR[q] * tapG[k]; el += (rL[q] * 0.6 + rR[q] * 0.2) * tapG[k]; }
            }
            if (++w == P) w = 0;
            double dL = pl + 0.3 * el, dR = pr + 0.3 * er;
            for (int k = 0; k < 4; k++) {
                double[] b = apL[k]; int p = apPL[k]; double y = -apg * dL + b[p]; b[p] = dL + apg * y; apPL[k] = (p + 1) % b.length; dL = y;
                b = apR[k]; p = apPR[k]; y = -apg * dR + b[p]; b[p] = dR + apg * y; apPR[k] = (p + 1) % b.length; dR = y;
            }
            double sum = 0;
            for (int j = 0; j < N; j++) { double v = dl[j][dp[j]]; lp[j] = v * (1 - damp) + lp[j] * damp; o[j] = lp[j] * g[j]; sum += o[j]; }
            double hh = sum * (2.0 / N), lateL = 0, lateR = 0;
            for (int j = 0; j < N; j++) {
                double inj = ((j & 1) == 0 ? dL : dR) * inS[j] * 0.35;
                dl[j][dp[j]] = o[j] - hh + inj + 1e-25;
                if (++dp[j] == dl[j].length) dp[j] = 0;
                lateL += o[j] * sL[j]; lateR += o[j] * sRr[j];
            }
            if (!loop || ii >= n) { oL[i] = (float) ((el * 0.9 + lateL * 1.1) * T.wet); oR[i] = (float) ((er * 0.9 + lateR * 1.1) * T.wet); }
        }
        double e = 0;
        for (int i = 0; i < n; i++) { T.L[i] += oL[i]; T.R[i] += oR[i]; e += oL[i] * oL[i] + oR[i] * oR[i]; }
        T.stems.put("~reverb", e);
    }

    static void eq(float[] x, boolean loop, Bq... chain) {
        int n = x.length;
        if (loop) for (int i = 0; i < n; i++) { double v = x[i]; for (Bq b : chain) v = b.p(v); }
        for (int i = 0; i < n; i++) { double v = x[i]; for (Bq b : chain) v = b.p(v); x[i] = (float) v; }
    }
    static Bq[] busEq(Track T) {
        return new Bq[]{Bq.hp(28, 0.707), Bq.shelf(120, T.lowShelf, 0.707, false), Bq.peak(3200, 0.9, T.presence), Bq.shelf(9500, -1.5, 0.707, true)};
    }

    static double[] compress(float[] L, float[] R, boolean loop, double thr, double ratio, double attMs, double relMs, double knee) {
        int n = L.length;
        double ca = Math.exp(-1 / (attMs * 0.001 * SR)), cr = Math.exp(-1 / (relMs * 0.001 * SR)), cd = Math.exp(-1 / (0.005 * SR));
        double det = 1e-9, gdb = 0, maxRed = 0, sumRed = 0;
        float[] gain = new float[n];
        int passes = loop ? 2 : 1;
        for (int p = 0; p < passes; p++)
            for (int i = 0; i < n; i++) {
                double s = 0.5 * (L[i] * L[i] + R[i] * R[i]);
                det = cd * det + (1 - cd) * s;
                double over = 10 * Math.log10(det + 1e-12) - thr, red;
                if (over <= -knee / 2) red = 0;
                else if (over >= knee / 2) red = over * (1 - 1 / ratio);
                else { double x = over + knee / 2; red = (1 - 1 / ratio) * x * x / (2 * knee); }
                double tgt = -red;
                gdb = tgt < gdb ? ca * gdb + (1 - ca) * tgt : cr * gdb + (1 - cr) * tgt;
                if (p == passes - 1) { gain[i] = (float) gdb; maxRed = Math.max(maxRed, -gdb); sumRed += -gdb; }
            }
        for (int i = 0; i < n; i++) { float g = (float) Math.pow(10, gain[i] / 20); L[i] *= g; R[i] *= g; }
        return new double[]{maxRed, sumRed / n};
    }

    static double[] slidingMin(double[] a, int h, boolean loop) {
        int n = a.length;
        double[] out = new double[n];
        int[] dq = new int[n + 2 * h + 2];
        int head = 0, tail = 0;
        for (int e = -h; e < n + h; e++) {
            double v = loop ? a[Math.floorMod(e, n)] : (e < 0 || e >= n ? 1.0 : a[e]);
            while (tail > head) {
                int q = dq[tail - 1];
                double qv = loop ? a[Math.floorMod(q, n)] : (q < 0 || q >= n ? 1.0 : a[q]);
                if (qv >= v) tail--; else break;
            }
            dq[tail++] = e;
            int c = e - h;
            if (c >= 0) {
                while (dq[head] < c - h) head++;
                int q = dq[head];
                out[c] = loop ? a[Math.floorMod(q, n)] : (q < 0 || q >= n ? 1.0 : a[q]);
            }
        }
        return out;
    }

    /** look-ahead peak limiter; gain curve never exceeds the required gain at any sample or cubic midpoint */
    static void limit(float[] L, float[] R, boolean loop, double ceilDb) {
        int n = L.length;
        double ceil = Math.pow(10, ceilDb / 20);
        int la = (int) (0.0025 * SR);
        double[] req = new double[n];
        for (int i = 0; i < n; i++) {
            double pk = Math.max(Math.abs(L[i]), Math.abs(R[i]));
            pk = Math.max(pk, Math.abs(mid(L, i, loop)));
            pk = Math.max(pk, Math.abs(mid(R, i, loop)));
            req[i] = pk > ceil ? ceil / pk : 1;
        }
        double[] mn = slidingMin(req, la, loop);
        double[] pre = new double[n + 2 * la + 2];
        for (int e = -la; e < n + la; e++) {
            double v = loop ? mn[Math.floorMod(e, n)] : (e < 0 || e >= n ? 1.0 : mn[e]);
            pre[e + la + 1] = pre[e + la] + v;
        }
        double[] av = new double[n];
        for (int i = 0; i < n; i++) av[i] = (pre[i + 2 * la + 1] - pre[i]) / (2 * la + 1);
        double c = 1 - Math.exp(-1 / (0.06 * SR)), g = 1;
        int passes = loop ? 2 : 1;
        for (int p = 0; p < passes; p++)
            for (int i = 0; i < n; i++) {
                double t = Math.min(av[i], req[i]);
                if (t < g) g = t; else g += c * (t - g);
                if (p == passes - 1) { L[i] *= (float) g; R[i] *= (float) g; }
            }
    }
    static double at(float[] x, int i, boolean loop) { int n = x.length; if (loop) return x[Math.floorMod(i, n)]; return i < 0 || i >= n ? 0 : x[i]; }
    static double mid(float[] x, int i, boolean loop) {
        return (-at(x, i - 1, loop) + 9 * at(x, i, loop) + 9 * at(x, i + 1, loop) - at(x, i + 2, loop)) / 16.0;
    }

    /** BS.1770-4 integrated loudness (K-weighting, 400 ms blocks, 75 % overlap, absolute + relative gate) */
    static double lufs(float[] L, float[] R) {
        Bq[] kl = kw(), kr = kw();
        int n = L.length, hop = (int) (0.1 * SR), segs = n / hop;
        double[] seg = new double[segs];
        for (int i = 0; i < segs * hop; i++) {
            double yl = kl[1].p(kl[0].p(L[i])), yr = kr[1].p(kr[0].p(R[i]));
            seg[i / hop] += yl * yl + yr * yr;
        }
        List<Double> z = new ArrayList<>();
        for (int b = 0; b + 4 <= segs; b++) z.add((seg[b] + seg[b + 1] + seg[b + 2] + seg[b + 3]) / (4.0 * hop));
        double s = 0; int c = 0;
        for (double v : z) if (-0.691 + 10 * Math.log10(v + 1e-20) > -70) { s += v; c++; }
        if (c == 0) return -100;
        double thr = -0.691 + 10 * Math.log10(s / c) - 10;
        s = 0; c = 0;
        for (double v : z) if (-0.691 + 10 * Math.log10(v + 1e-20) > Math.max(-70, thr)) { s += v; c++; }
        return -0.691 + 10 * Math.log10(s / c);
    }
    static Bq[] kw() {
        double fc = 1681.9744509555319, G = 3.99984385397, Q = 0.7071752369554193;
        double A = Math.pow(10, G / 40), w = TAU * fc / SR, cs = Math.cos(w), al = Math.sin(w) / (2 * Q), sa = 2 * Math.sqrt(A) * al;
        Bq s1 = new Bq(A * ((A + 1) + (A - 1) * cs + sa), -2 * A * ((A - 1) + (A + 1) * cs), A * ((A + 1) + (A - 1) * cs - sa),
                (A + 1) - (A - 1) * cs + sa, 2 * ((A - 1) - (A + 1) * cs), (A + 1) - (A - 1) * cs - sa);
        Bq s2 = Bq.hp(38.13547087613982, 0.5003270373253953);
        return new Bq[]{s1, s2};
    }
    static void scale(float[] L, float[] R, double g) { for (int i = 0; i < L.length; i++) { L[i] *= (float) g; R[i] *= (float) g; } }
    static double db(double g) { return 20 * Math.log10(g); }

    static String master(Track T, StringBuilder rep) {
        reverb(T);
        float[] L = T.L, R = T.R;
        int n = T.n;
        eq(L, T.loop, busEq(T)); eq(R, T.loop, busEq(T));
        if (!T.loop) {
            int fi = (int) (T.fadeIn * SR), fo = (int) (T.fadeOut * SR);
            for (int i = 0; i < fi; i++) { float g = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fi)); L[i] *= g; R[i] *= g; }
            for (int i = 0; i < fo; i++) { float g = (float) (0.5 - 0.5 * Math.cos(Math.PI * i / fo)); L[n - 1 - i] *= g; R[n - 1 - i] *= g; }
            L[n - 1] = 0; R[n - 1] = 0;
        }
        scale(L, R, Math.pow(10, (-20 - lufs(L, R)) / 20));
        double[] cr = compress(L, R, T.loop, -20, 2.2, 15, 180, 8);
        float[] L0 = L.clone(), R0 = R.clone();
        double gain = Math.pow(10, (T.target - lufs(L0, R0)) / 20), m = 0;
        for (int it = 0; it < 5; it++) {
            for (int i = 0; i < n; i++) { L[i] = (float) (L0[i] * gain); R[i] = (float) (R0[i] * gain); }
            limit(L, R, T.loop, -1.7);
            m = lufs(L, R);
            if (Math.abs(m - T.target) < 0.1) break;
            gain *= Math.pow(10, (T.target - m) / 20);
        }
        rep.append(String.format("  comp: max GR %.1f dB, avg GR %.2f dB; final make-up %.1f dB; java LUFS %.2f%n", cr[0], cr[1], db(gain), m));
        return "";
    }

    static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -TAU / len, wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi; re[a] += xr; im[a] += xi;
                    double ncr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = ncr;
                }
            }
        }
    }
    static String bands(float[] L, float[] R) {
        int N = 8192;
        double[] acc = new double[N / 2], win = new double[N];
        for (int i = 0; i < N; i++) win[i] = 0.5 - 0.5 * Math.cos(TAU * i / N);
        int frames = 0;
        for (int s = 0; s + N <= L.length; s += N * 2) {
            double[] re = new double[N], im = new double[N];
            for (int i = 0; i < N; i++) re[i] = 0.5 * (L[s + i] + R[s + i]) * win[i];
            fft(re, im);
            for (int k = 0; k < N / 2; k++) acc[k] += re[k] * re[k] + im[k] * im[k];
            frames++;
        }
        double[] ed = {20, 60, 120, 250, 500, 1000, 2000, 4000, 8000, 16000};
        StringBuilder sb = new StringBuilder("  bands dB (20-60|60-120|120-250|250-500|500-1k|1-2k|2-4k|4-8k|8-16k):");
        for (int b = 0; b + 1 < ed.length; b++) {
            double e = 0;
            for (int k = 0; k < N / 2; k++) { double f = k * (double) SR / N; if (f >= ed[b] && f < ed[b + 1]) e += acc[k]; }
            sb.append(String.format(" %.1f", 10 * Math.log10(e / Math.max(1, frames) + 1e-20) - 70));
        }
        return sb.toString();
    }

    static String analyse(Track T, short[] qL, short[] qR) {
        StringBuilder sb = new StringBuilder();
        int n = T.n;
        double mL = 0, mR = 0, pk = 0;
        for (int i = 0; i < n; i++) { mL += qL[i]; mR += qR[i]; pk = Math.max(pk, Math.max(Math.abs(qL[i]), Math.abs(qR[i]))); }
        sb.append(String.format("  sample peak %.2f dBFS, DC L %.2e R %.2e (full scale)%n", db(pk / 32768.0), mL / n / 32768.0, mR / n / 32768.0));
        if (T.loop) {
            int w = (int) (0.05 * SR);
            int maxStep = 0;
            double eS = 0, eE = 0;
            for (int i = 1; i < w; i++) {
                maxStep = Math.max(maxStep, Math.max(Math.abs(qL[i] - qL[i - 1]), Math.abs(qR[i] - qR[i - 1])));
                maxStep = Math.max(maxStep, Math.max(Math.abs(qL[n - i] - qL[n - i - 1]), Math.abs(qR[n - i] - qR[n - i - 1])));
            }
            for (int i = 0; i < w; i++) { eS += (double) qL[i] * qL[i] + (double) qR[i] * qR[i]; eE += (double) qL[n - 1 - i] * qL[n - 1 - i] + (double) qR[n - 1 - i] * qR[n - 1 - i]; }
            int jL = Math.abs(qL[0] - qL[n - 1]), jR = Math.abs(qR[0] - qR[n - 1]);
            sb.append(String.format("  loop: %d samples = %.4f s = %d bars @ %.0f BPM (exact bar length %.3f samples); wrap step L %d R %d vs max step in edge 50 ms %d; RMS last 50 ms %.1f dB, first 50 ms %.1f dB%n",
                    n, n / (double) SR, T.bars, T.bpm, T.bars * 4 * 60.0 / T.bpm * SR, jL, jR, maxStep,
                    10 * Math.log10(eE / (2.0 * w) / (32768.0 * 32768.0) + 1e-20), 10 * Math.log10(eS / (2.0 * w) / (32768.0 * 32768.0) + 1e-20)));
        }
        float[] fL = new float[n], fR = new float[n];
        for (int i = 0; i < n; i++) { fL[i] = qL[i] / 32768f; fR[i] = qR[i] / 32768f; }
        sb.append(bands(fL, fR)).append('\n');
        int parts = 24;
        sb.append("  RMS timeline (dBFS, ").append(parts).append(" slices):");
        for (int p = 0; p < parts; p++) {
            int a = n * p / parts, b = n * (p + 1) / parts;
            double s = 0;
            for (int i = a; i < b; i++) s += fL[i] * fL[i] + fR[i] * fR[i];
            sb.append(String.format(" %.0f", 10 * Math.log10(s / (2.0 * (b - a)) + 1e-12)));
        }
        sb.append('\n');
        double tot = 0;
        for (double e : T.stems.values()) tot += e;
        sb.append("  stems (dB rel. sum):");
        for (Map.Entry<String, Double> e : T.stems.entrySet()) sb.append(String.format(" %s %.1f", e.getKey(), 10 * Math.log10(e.getValue() / tot)));
        sb.append('\n');
        return sb.toString();
    }

    static void writeWav(Path p, short[] L, short[] R) throws IOException {
        int n = L.length;
        ByteBuffer bb = ByteBuffer.allocate(44 + n * 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + n * 4).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        bb.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 2).putInt(SR).putInt(SR * 4).putShort((short) 4).putShort((short) 16);
        bb.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(n * 4);
        for (int i = 0; i < n; i++) { bb.putShort(L[i]); bb.putShort(R[i]); }
        Files.write(p, bb.array());
    }

    static short[] quantise(float[] x, Random rn) {
        short[] q = new short[x.length];
        for (int i = 0; i < x.length; i++) {
            double v = x[i] * 32767.0 + (rn.nextDouble() - rn.nextDouble());
            q[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v)));
        }
        return q;
    }

    public static void main(String[] a) throws Exception {
        Path outDir = Paths.get(a.length > 0 ? a[0] : "blood/src/main/resources/assets/hha_blood/sounds/music");
        Path work = Paths.get(a.length > 1 ? a[1] : "build/music_work");
        String ff = a.length > 2 && !a[2].isEmpty() ? a[2] : null;
        Set<String> only = a.length > 3 ? new HashSet<>(Arrays.asList(a[3].split(","))) : null;
        Files.createDirectories(outDir);
        Files.createDirectories(work);
        Map<String, Callable<Track>> songs = new LinkedHashMap<>();
        songs.put("pit_ambient", GenBossMusic::ambient);
        songs.put("tension", GenBossMusic::tension);
        songs.put("cerberus", GenBossMusic::cerberus);
        songs.put("cerberus_unleashed", GenBossMusic::unleashed);
        songs.put("minotaur", GenBossMusic::minotaur);
        songs.put("minotaur_final", GenBossMusic::fin);
        songs.put("victory", GenBossMusic::victory);
        ExecutorService ex = Executors.newFixedThreadPool(7);
        List<Future<String>> fs = new ArrayList<>();
        long t00 = System.nanoTime();
        for (Map.Entry<String, Callable<Track>> e : songs.entrySet()) {
            if (only != null && !only.contains(e.getKey())) continue;
            fs.add(ex.submit(() -> {
                long t0 = System.nanoTime();
                Track T = e.getValue().call();
                long t1 = System.nanoTime();
                StringBuilder rep = new StringBuilder();
                master(T, rep);
                Random dr = new Random(T.name.hashCode());
                short[] qL = quantise(T.L, dr), qR = quantise(T.R, dr);
                String head = String.format("%s: %.2f s, %s, target %.0f LUFS, render %.1f s + master %.1f s%n", T.name, T.n / (double) SR,
                        T.loop ? "loop " + T.bars + " bars @ " + T.bpm + " BPM" : "one-shot", T.target, (t1 - t0) / 1e9, (System.nanoTime() - t1) / 1e9);
                String an = analyse(T, qL, qR);
                Path wav = work.resolve(T.name + ".wav");
                writeWav(wav, qL, qR);
                if (ff != null) {
                    Path ogg = outDir.resolve(T.name + ".ogg");
                    Process pr = new ProcessBuilder(ff, "-y", "-loglevel", "error", "-i", wav.toString(), "-c:a", "libvorbis", "-q:a", String.valueOf(T.vq), ogg.toString())
                            .redirectErrorStream(true).start();
                    String out = new String(pr.getInputStream().readAllBytes());
                    int rc = pr.waitFor();
                    rep.append(String.format("  ogg: %s (%d bytes) rc=%d %s%n", ogg.getFileName(), Files.exists(ogg) ? Files.size(ogg) : -1, rc, out.trim()));
                }
                return head + rep + an;
            }));
        }
        for (Future<String> f : fs) System.out.print(f.get());
        ex.shutdown();
        System.out.printf("total %.1f s%n", (System.nanoTime() - t00) / 1e9);
    }
}
