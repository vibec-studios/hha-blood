import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;

/**
 * Procedural sound design for the "Schlachtgrube" boss fight (Crimson Minotaur "Der Schlaechter" + Blood Cerberus).
 * Everything is synthesised, no samples. Deterministic: every sound reseeds its RNG from its own name.
 *
 * Voices: band-limited glottal pulse trains (LF-like flow derivative, rendered 4x oversampled and decimated with a
 * windowed-sinc FIR) with per-period jitter / shimmer / period doubling (subharmonics), aspiration noise gated by the
 * glottal open phase, a parallel formant bank (time-varying TPT state-variable band-passes, vowel morphing),
 * chest resonance, gentle tanh saturation and a mono Freeverb-style room.
 *
 * Usage:
 *   java tools/sounds/GenBossSounds.java <wavDir>                         (WAV only, 16-bit mono 44.1 kHz)
 *   java tools/sounds/GenBossSounds.java <wavDir> <ffmpeg.exe> <oggDir>   (+ Vorbis q5 mono OGGs, peak-checked)
 * Optional 4th argument: a comma separated list of sound names to render only those.
 */
public class GenBossSounds {
    static final int SR = 44100;
    static final double TARGET = Math.pow(10, -1.0 / 20);   // -1 dBFS
    static Random rnd = new Random(1);

    // ================================================================ basics
    static int ns(double s) { return (int) Math.round(s * SR); }
    static double[] buf(double s) { return new double[ns(s)]; }
    static double rr(double a, double b) { return a + rnd.nextDouble() * (b - a); }
    static double g() { return clamp(rnd.nextGaussian(), -2.5, 2.5); }
    static double clamp(double v, double a, double b) { return Math.max(a, Math.min(b, v)); }
    static double db(double d) { return Math.pow(10, d / 20); }

    interface Curve { double at(double t); }
    static Curve k(double v) { return t -> v; }

    /** breakpoints t0,v0,t1,v1,... with cosine (smooth) interpolation */
    static Curve curve(double... tv) {
        return t -> {
            if (t <= tv[0]) return tv[1];
            for (int i = 2; i < tv.length; i += 2) {
                if (t <= tv[i]) {
                    double u = (t - tv[i - 2]) / (tv[i] - tv[i - 2]);
                    u = 0.5 - 0.5 * Math.cos(Math.PI * u);
                    return tv[i - 1] + (tv[i + 1] - tv[i - 1]) * u;
                }
            }
            return tv[tv.length - 1];
        };
    }

    /** smooth random curve roughly in [-1,1], 'rate' control points per second (Catmull-Rom) */
    static Curve wobble(double rate) {
        double[] p = new double[(int) (rate * 14) + 8];
        for (int i = 0; i < p.length; i++) p[i] = rnd.nextDouble() * 2 - 1;
        return t -> {
            double x = Math.max(0, t) * rate;
            int i = Math.min((int) x, p.length - 4);
            double u = Math.min(1, x - i);
            double p0 = p[i], p1 = p[i + 1], p2 = p[i + 2], p3 = p[i + 3];
            return 0.5 * (2 * p1 + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u * u + (-p0 + 3 * p1 - 3 * p2 + p3) * u * u * u);
        };
    }

    /** raised-cosine attack, then exponential decay */
    static double env(double t, double a, double d) {
        if (t < 0) return 0;
        if (t < a) return 0.5 - 0.5 * Math.cos(Math.PI * t / a);
        return Math.exp(-(t - a) / d);
    }

    static void mix(double[] dst, double[] src, double at, double gain) {
        int o = ns(at);
        for (int i = 0; i < src.length; i++) {
            int j = o + i;
            if (j >= dst.length) break;
            if (j >= 0) dst[j] += src[i] * gain;
        }
    }

    static double[] amp(double[] x, Curve c) {
        for (int i = 0; i < x.length; i++) x[i] *= c.at(i / (double) SR);
        return x;
    }

    static double peak(double[] x) { double m = 0; for (double v : x) m = Math.max(m, Math.abs(v)); return m; }
    static double rms(double[] x) { double s = 0; for (double v : x) s += v * v; return Math.sqrt(s / Math.max(1, x.length)); }
    static double[] norm(double[] x, double p) { double m = peak(x); if (m > 0) for (int i = 0; i < x.length; i++) x[i] *= p / m; return x; }
    static double[] rmsTo(double[] x, double r) { double m = rms(x); if (m > 0) for (int i = 0; i < x.length; i++) x[i] *= r / m; return x; }

    static void fadeOut(double[] x, double s) {
        int f = Math.min(ns(s), x.length);
        for (int i = 0; i < f; i++) x[x.length - 1 - i] *= 0.5 - 0.5 * Math.cos(Math.PI * i / f);
    }

    static void fadeIn(double[] x, double s) {
        int f = Math.min(ns(s), x.length);
        for (int i = 0; i < f; i++) x[i] *= 0.5 - 0.5 * Math.cos(Math.PI * i / f);
    }

    /** replace the amplitude envelope (20 ms RMS) of x by the target curve, gain limited */
    static double[] shapeEnv(double[] x, Curve target, double maxGain) {
        int w = ns(0.02);
        double[] sq = new double[x.length + 1];
        for (int i = 0; i < x.length; i++) sq[i + 1] = sq[i] + x[i] * x[i];
        double ref = rms(x);
        double[] e = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            int a = Math.max(0, i - w), b = Math.min(x.length, i + w);
            e[i] = Math.sqrt((sq[b] - sq[a]) / (b - a)) / ref;
        }
        e = lp(e, 30);
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] * Math.min(maxGain, target.at(i / (double) SR) / Math.max(1e-4, e[i]));
        return y;
    }

    static double[] reverse(double[] x) { double[] r = new double[x.length]; for (int i = 0; i < x.length; i++) r[i] = x[x.length - 1 - i]; return r; }

    /** gentle tanh saturation, keeps the peak level */
    static double[] sat(double[] x, double drive) {
        double m = peak(x);
        if (m == 0) return x;
        double n = Math.tanh(drive);
        for (int i = 0; i < x.length; i++) x[i] = Math.tanh(drive * x[i] / m) / n * m;
        return x;
    }

    // ================================================================ filters
    /** TPT state-variable filter (stable under modulation). mode 0 = low-pass, 1 = band-pass (unity peak), 2 = high-pass */
    static double[] svf(double[] x, Curve f, Curve q, int mode) {
        double[] y = new double[x.length];
        double s1 = 0, s2 = 0;
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) SR;
            double fc = clamp(f.at(t), 8, SR * 0.45), kk = 1 / Math.max(0.3, q.at(t));
            double gg = Math.tan(Math.PI * fc / SR), h = 1 / (1 + gg * (gg + kk));
            double hp = (x[i] - (gg + kk) * s1 - s2) * h;
            double v1 = gg * hp, bp = v1 + s1; s1 = bp + v1;
            double v2 = gg * bp, lp = v2 + s2; s2 = lp + v2;
            y[i] = mode == 0 ? lp : mode == 1 ? kk * bp : hp;
        }
        return y;
    }

    static double[] lp(double[] x, double f) { return svf(x, k(f), k(0.7071), 0); }
    static double[] lp(double[] x, Curve f, double q) { return svf(x, f, k(q), 0); }
    static double[] hp(double[] x, double f) { return svf(x, k(f), k(0.7071), 2); }
    static double[] bp(double[] x, double f, double q) { return svf(x, k(f), k(q), 1); }
    static double[] bp(double[] x, Curve f, double q) { return svf(x, f, k(q), 1); }

    /** peaking EQ (bell) */
    static double[] peakEq(double[] x, double f, double q, double gainDb) {
        double[] b = bp(x, f, q);
        double gg = db(gainDb) - 1;
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) y[i] = x[i] + gg * b[i];
        return y;
    }

    /** shared voice post chain: remove rumble, add throat presence, saturate */
    static double[] beastPost(double[] s, double drive, double presenceHz, double presenceDb) {
        s = hp(s, 40);
        s = peakEq(s, presenceHz, 0.8, presenceDb);
        s = lp(s, 7500);
        return sat(s, drive);
    }

    // ================================================================ noise
    static double[] white(int n) { double[] r = new double[n]; for (int i = 0; i < n; i++) r[i] = rnd.nextDouble() * 2 - 1; return r; }

    /** pink noise (Paul Kellet's refined filter) */
    static double[] pink(int n) {
        double[] r = new double[n];
        double b0 = 0, b1 = 0, b2 = 0, b3 = 0, b4 = 0, b5 = 0, b6 = 0;
        for (int i = 0; i < n; i++) {
            double w = rnd.nextDouble() * 2 - 1;
            b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759; b2 = 0.96900 * b2 + w * 0.1538520;
            b3 = 0.86650 * b3 + w * 0.3104856; b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980;
            r[i] = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11;
            b6 = w * 0.115926;
        }
        return r;
    }

    static double[] brown(int n) {
        double[] r = new double[n];
        double v = 0;
        for (int i = 0; i < n; i++) { v = v * 0.996 + (rnd.nextDouble() * 2 - 1) * 0.1; r[i] = v; }
        return rmsTo(hp(r, 15), 0.3);
    }

    // ================================================================ glottal voice source
    static final int OS = 4;
    static final double[] FIR = fir();

    /** Blackman windowed sinc, cutoff 19 kHz at 4x rate */
    static double[] fir() {
        int L = 48 * OS + 1, M = L / 2;
        double fc = 19000.0 / (SR * OS), sum = 0;
        double[] h = new double[L];
        for (int j = 0; j < L; j++) {
            int x = j - M;
            double s = x == 0 ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double w = 0.42 - 0.5 * Math.cos(2 * Math.PI * j / (L - 1)) + 0.08 * Math.cos(4 * Math.PI * j / (L - 1));
            h[j] = s * w; sum += h[j];
        }
        for (int j = 0; j < L; j++) h[j] /= sum;
        return h;
    }

    static double[] decimate(double[] d, int outLen) {
        double[] y = new double[outLen];
        int M = FIR.length / 2;
        for (int m = 0; m < outLen; m++) {
            int c = m * OS - M;
            int j0 = Math.max(0, -c), j1 = Math.min(FIR.length, d.length - c);
            double s = 0;
            for (int j = j0; j < j1; j++) s += FIR[j] * d[c + j];
            y[m] = s;
        }
        return y;
    }

    static final class Voice {
        Curve f0, amp = k(1), jitter = k(0.01), shimmer = k(0.06), sub = k(0);
        double subT = 0.05;   // period alternation for subharmonics (period doubling)
        double oq = 0.6;      // open quotient
        double sq = 0.72;     // opening part of the open phase
        double ret = 0.02;    // return phase (fraction of the period): small = pressed/bright, large = soft/dark
        double fry = 0;       // probability of an irregular long (creaky) period
        Voice(Curve f0) { this.f0 = f0; }
    }

    /** LF-like glottal flow derivative pulses, one per (jittered) period. Returns {source, open-phase gate}. */
    static double[][] glottal(double len, Voice v) {
        double osr = SR * (double) OS;
        int N = (int) Math.ceil(len * osr) + FIR.length;
        double[] d = new double[N], gt = new double[N];
        double t = 0;
        int k = 0;
        while (t < len) {
            double f = clamp(v.f0.at(t), 15, 2500), T = 1 / f;
            double sd = clamp(v.sub.at(t), 0, 1);
            boolean odd = (k & 1) == 1;
            T *= 1 + v.jitter.at(t) * g();
            T *= odd ? 1 - v.subT * sd : 1 + v.subT * sd;
            if (v.fry > 0 && rnd.nextDouble() < v.fry) T *= rr(1.3, 2.1);
            T = Math.max(T, 1 / 3000.0);
            double A = v.amp.at(t) * Math.max(0, 1 + v.shimmer.at(t) * g()) * (odd ? 1 - 0.7 * sd : 1);
            double Te = v.oq * T, Tp = v.sq * Te, Ta = Math.max(v.ret * T, 1.0 / osr);
            double cl = (Math.PI / 2) / (Te - Tp);
            double c = 1 / (1 + cl * Ta);          // makes the net flow return to zero (no DC per pulse)
            int i0 = (int) Math.ceil(t * osr), i1 = (int) Math.ceil((t + Te + 7 * Ta) * osr);
            if (A > 0) for (int i = i0; i < i1 && i < N; i++) {
                double u = i / osr - t, val;
                if (u < Tp) val = 0.5 * Math.PI / Tp * Math.sin(Math.PI * u / Tp);
                else if (u < Te) val = -c * cl * Math.sin(Math.PI / 2 * (u - Tp) / (Te - Tp));
                else val = -c * cl * Math.exp(-(u - Te) / Ta);
                d[i] += val * T * A;
                if (u < Te) gt[i] = Math.max(gt[i], A);
            }
            t += T;
            k++;
        }
        int n = ns(len);
        double[] src = decimate(d, n);
        double[] gate = new double[n];
        for (int m = 0; m < n; m++) gate[m] = gt[Math.min(m * OS, N - 1)];
        return new double[][]{src, lp(gate, 1500)};
    }

    // ================================================================ formants
    static final Map<String, double[]> VOWELS = new HashMap<>();
    static {
        VOWELS.put("aa", new double[]{730, 1090, 2440, 3400, 4200});
        VOWELS.put("ah", new double[]{640, 1190, 2390, 3400, 4200});
        VOWELS.put("aw", new double[]{570, 840, 2410, 3350, 4200});
        VOWELS.put("oh", new double[]{460, 760, 2500, 3350, 4200});
        VOWELS.put("oo", new double[]{300, 870, 2240, 3300, 4200});
        VOWELS.put("uh", new double[]{560, 1190, 2390, 3350, 4200});
        VOWELS.put("eh", new double[]{530, 1840, 2480, 3500, 4300});
        VOWELS.put("ee", new double[]{270, 2290, 3010, 3500, 4300});
        VOWELS.put("ae", new double[]{660, 1720, 2410, 3400, 4300});
        VOWELS.put("mm", new double[]{250, 1100, 2300, 3300, 4200});
        VOWELS.put("er", new double[]{490, 1350, 1690, 3300, 4200});
    }

    /** vowel path: size scales the vocal tract (0.5 = twice as long as an adult man), then time,vowel pairs */
    static final class Vow {
        final double[] ts;
        final double[][] fs;
        Vow(double size, Object... tv) {
            ts = new double[tv.length / 2];
            fs = new double[tv.length / 2][];
            for (int i = 0; i < ts.length; i++) {
                ts[i] = ((Number) tv[2 * i]).doubleValue();
                double[] b = VOWELS.get((String) tv[2 * i + 1]);
                fs[i] = new double[b.length];
                for (int j = 0; j < b.length; j++) fs[i][j] = b[j] * size;
            }
        }
        double f(int j, double t) {
            if (t <= ts[0]) return fs[0][j];
            for (int i = 1; i < ts.length; i++) {
                if (t <= ts[i]) {
                    double u = (t - ts[i - 1]) / (ts[i] - ts[i - 1]);
                    u = 0.5 - 0.5 * Math.cos(Math.PI * u);
                    return fs[i - 1][j] + (fs[i][j] - fs[i - 1][j]) * u;
                }
            }
            return fs[ts.length - 1][j];
        }
    }

    static final double[] G_BEAST = {0, -1, -5, -10, -15};
    static final double[] G_DOG = {0, -1, -4, -9, -14};
    static final double[] G_HOWL = {0, -5, -13, -20, -26};

    /** parallel formant bank, alternating signs so the skirts add instead of cancelling */
    static double[] formants(double[] src, Vow vw, double[] gdb, double bw) {
        double[] out = new double[src.length];
        Curve w = wobble(2.5);
        for (int j = 0; j < gdb.length; j++) {
            final int jj = j;
            Curve fc = t -> vw.f(jj, t) * (1 + 0.025 * w.at(t + jj * 0.37));
            Curve q = t -> { double F = vw.f(jj, t); return F / ((50 + 0.08 * F) * bw); };
            double[] y = svf(src, fc, q, 1);
            double gg = db(gdb[j]) * (j % 2 == 0 ? 1 : -1);
            for (int i = 0; i < out.length; i++) out[i] += y[i] * gg;
        }
        return out;
    }

    /** a full voice layer, peak-normalised to 1 */
    static double[] voice(double len, Voice v, Vow vw, double breath, double[] gdb, double bw, double chest) {
        double[][] gs = glottal(len, v);
        double[] src = gs[0], gate = gs[1];
        rmsTo(src, 1);
        double[] chestSig = chest > 0 ? lp(lp(src, 230), 230) : null;
        if (breath > 0) {
            double[] asp = hp(pink(src.length), 350);
            for (int i = 0; i < asp.length; i++) asp[i] *= 0.3 * v.amp.at(i / (double) SR) + 0.7 * gate[i];
            rmsTo(asp, breath);
            for (int i = 0; i < src.length; i++) src[i] += asp[i];
        }
        double[] out = formants(src, vw, gdb, bw);
        rmsTo(out, 1);
        if (chestSig != null) {
            rmsTo(chestSig, chest);
            for (int i = 0; i < out.length; i++) out[i] += chestSig[i];
        }
        return norm(out, 1);
    }

    /** irregular amplitude modulation (false-fold rattle / growl) */
    static void roughAM(double[] x, double rate, double depth, double irr) {
        Curve w = wobble(rate * 0.4);
        double ph = 0;
        for (int i = 0; i < x.length; i++) {
            double t = i / (double) SR;
            ph += 2 * Math.PI * rate * (1 + irr * w.at(t)) / SR;
            x[i] *= 1 - depth * (0.5 + 0.5 * Math.sin(ph));
        }
    }

    // ================================================================ reverb (mono Freeverb)
    static double[] reverb(double[] x, double size, double fb, double damp, double wet, double tail, double pre) {
        return reverb(x, size, fb, damp, wet, tail, pre, 0, 1);
    }

    static double[] reverb(double[] x, double size, double fb, double damp, double wet, double tail, double pre, double lpHz, double dry) {
        int[] cd = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        int[] ad = {556, 441, 341, 225};
        int n = x.length + ns(tail), pd = ns(pre);
        double[] in = new double[n], out = new double[n];
        for (int i = 0; i < x.length && i + pd < n; i++) in[i + pd] = x[i];
        for (int c = 0; c < cd.length; c++) {
            int d = Math.max(1, (int) (cd[c] * size));
            double[] line = new double[d];
            int p = 0;
            double store = 0;
            for (int i = 0; i < n; i++) {
                double o = line[p];
                store = o * (1 - damp) + store * damp;
                line[p] = in[i] + store * fb;
                p = (p + 1) % d;
                out[i] += o;
            }
        }
        for (int a = 0; a < ad.length; a++) {
            int d = Math.max(1, (int) (ad[a] * size));
            double[] line = new double[d];
            int p = 0;
            for (int i = 0; i < n; i++) {
                double bo = line[p];
                double o = -out[i] + bo;
                line[p] = out[i] + bo * 0.5;
                p = (p + 1) % d;
                out[i] = o;
            }
        }
        if (lpHz > 0) out = lp(out, lpHz);
        out = hp(out, 40);
        double rd = rms(x), rw = rms(out), gw = rw > 0 ? wet * rd / rw : 0;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (i < x.length ? x[i] * dry : 0) + out[i] * gw;
        return r;
    }

    // ================================================================ building blocks
    /** pitched thump: sine sweeping f0 -> f1, optional click */
    static double[] thump(double f0, double f1, double sweep, double len, double decay, double click) {
        double[] r = buf(len);
        double ph = 0;
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            double f = f1 + (f0 - f1) * Math.exp(-t * sweep);
            ph += 2 * Math.PI * f / SR;
            r[i] = (Math.sin(ph) + 0.18 * Math.sin(2 * ph)) * env(t, 0.002, decay);
        }
        if (click > 0) {
            double[] c = hp(white(ns(0.012)), 1200);
            for (int i = 0; i < c.length; i++) c[i] *= env(i / (double) SR, 0.0004, 0.0025);
            mix(r, c, 0, click);
        }
        fadeOut(r, 0.01);
        return r;
    }

    /** band-limited noise burst, peak about 1 */
    static double[] noiseHit(double len, double lo, double hi, double attack, double decay) {
        double[] w = white(ns(len));
        if (hi > 0) w = lp(lp(w, hi), hi);
        if (lo > 0) w = hp(w, lo);
        rmsTo(w, 0.33);
        for (int i = 0; i < w.length; i++) w[i] *= env(i / (double) SR, attack, decay);
        fadeOut(w, 0.01);
        return w;
    }

    static double sinIf(double f, double t, double ph) { return f < 19000 ? Math.sin(2 * Math.PI * f * t + ph) : 0; }

    /** inharmonic metal (circular plate modes) with beating pairs and a strike transient */
    static double[] clang(double base, double len, double decay, double bright) {
        double[] ratios = {1.0, 1.594, 2.136, 2.296, 2.653, 2.918, 3.156, 3.501, 4.060, 4.604, 5.130, 5.906, 6.746, 7.412, 8.31, 9.27};
        double[] r = buf(len);
        for (int p = 0; p < ratios.length; p++) {
            double f = base * ratios[p] * (1 + 0.004 * g());
            if (f > 17000) continue;
            double a = Math.pow(bright, p) * rr(0.6, 1.0), dec = decay / (1 + 0.33 * p) * rr(0.75, 1.2);
            double f2 = f * (1 + rr(0.0008, 0.004)), ph1 = rr(0, 6.28), ph2 = rr(0, 6.28);
            for (int i = 0; i < r.length; i++) {
                double t = i / (double) SR, e = env(t, 0.0004, dec);
                if (e < 1e-5 && t > 0.01) break;
                r[i] += a * e * (Math.sin(2 * Math.PI * f * t + ph1) * 0.6 + sinIf(f2, t, ph2) * 0.4);
            }
        }
        double[] c = hp(white(ns(0.02)), 2500);
        for (int i = 0; i < c.length; i++) c[i] *= env(i / (double) SR, 0.0003, 0.004);
        norm(r, 1);
        mix(r, c, 0, 0.6);
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** a small stone/pebble tick */
    static void pebble(double[] r, double at, double a, double fLo, double fHi) {
        int o = ns(at);
        double f1 = fLo * Math.pow(fHi / fLo, rnd.nextDouble()), f2 = f1 * rr(1.4, 2.6), dec = rr(0.003, 0.018);
        int L = ns(dec * 6);
        double p1 = rr(0, 6.28), p2 = rr(0, 6.28);
        for (int i = 0; i < L && o + i < r.length; i++) {
            if (o + i < 0) continue;
            double t = i / (double) SR, e = env(t, 0.0004, dec);
            r[o + i] += a * (e * (Math.sin(2 * Math.PI * f1 * t + p1) * 0.7 + sinIf(f2, t, p2) * 0.4)
                    + (rnd.nextDouble() * 2 - 1) * 0.5 * env(t, 0.0002, 0.0012));
        }
    }

    /** falling rock debris: Poisson pebbles with decaying density */
    static double[] debris(double len, double rate0, double decay, double fLo, double fHi) {
        double[] r = buf(len);
        double t = rr(0, 0.01);
        while (true) {
            double rate = rate0 * Math.exp(-t / decay) + 1.5;
            t += -Math.log(1 - rnd.nextDouble()) / rate;
            if (t >= len) break;
            double a = (0.1 + 0.9 * Math.pow(rnd.nextDouble(), 2.2)) * Math.exp(-t / (decay * 1.6));
            pebble(r, t, a, fLo, fHi);
        }
        fadeOut(r, 0.05);
        return norm(r, 1);
    }

    /** crunching / cracking stone: dense random clicks through a band-pass */
    static double[] crunch(double len, double density, double centre, double decay) {
        double[] r = buf(len);
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            if (rnd.nextDouble() < density * Math.exp(-t / decay) / SR * 1000) {
                double a = 0.3 + rnd.nextDouble() * 0.7;
                int w = 15 + rnd.nextInt(110);
                for (int k = 0; k < w && i + k < r.length; k++) r[i + k] += (rnd.nextDouble() * 2 - 1) * a * (1 - k / (double) w);
            }
        }
        Curve wb = wobble(20);
        double[] b = bp(r, t -> centre * (1 + 0.3 * wb.at(t)), 1.2);
        for (int i = 0; i < b.length; i++) b[i] *= env(i / (double) SR, 0.001, decay);
        fadeOut(b, 0.02);
        return norm(b, 1);
    }

    /** blade / body whoosh: band-passed noise whose centre rises to the closest point and falls after */
    static double[] whoosh(double len, double peakT, double f0, double fp, double f1, double q, double whistle, double sharp) {
        int n = ns(len);
        Curve fc = t -> t < peakT ? f0 * Math.pow(fp / f0, Math.pow(t / peakT, 1.6))
                : fp * Math.pow(f1 / fp, Math.pow((t - peakT) / (len - peakT), 0.6));
        Curve e = t -> {
            if (t < peakT) return Math.pow(t / peakT, sharp);
            double u = (t - peakT) / (len - peakT);
            return Math.exp(-u * 4.5) * (1 - u * u * u);
        };
        double[] a = rmsTo(bp(pink(n), fc, q), 1);
        double[] b = rmsTo(bp(pink(n), t -> fc.at(t) * 2.4, 1.6), 0.45);
        double[] c = rmsTo(bp(white(n), t -> fc.at(t) * 3.3, 14), whistle);
        double[] lo = rmsTo(lp(pink(n), t -> fc.at(t) * 0.35, 0.7), 0.6);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (a[i] + b[i] + c[i] + lo[i]) * e.at(i / (double) SR);
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** breath / exhale noise through a resonance */
    static double[] breath(double len, double centre, double q, Curve amp) {
        int n = ns(len);
        double[] w = pink(n);
        double[] a = rmsTo(bp(w, centre, q), 1), b = rmsTo(hp(w, 1500), 0.35);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (a[i] + b[i]) * amp.at(i / (double) SR);
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** bull nostril blast: noisy, nasal resonances, nostril flutter, low air push */
    static double[] blast(double len, double decay, double flutter) {
        int n = ns(len);
        double[] w = white(n);
        double[] r = new double[n];
        double[][] parts = {
            rmsTo(bp(w, 480, 2.2), 1.0), rmsTo(bp(w, 1250, 2.6), 0.7), rmsTo(bp(w, 2700, 3), 0.4), rmsTo(hp(w, 4200), 0.18)
        };
        double[] low = rmsTo(lp(lp(pink(n), 170), 170), 0.9);
        Curve wb = wobble(12);
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            ph += 2 * Math.PI * flutter * (1 + 0.25 * wb.at(t)) / SR;
            double fl = 1 - 0.55 * (0.5 + 0.5 * Math.sin(ph));
            double e = env(t, 0.012, decay);
            r[i] = (parts[0][i] + parts[1][i] + parts[2][i] + parts[3][i]) * fl * e + low[i] * env(t, 0.008, decay * 0.7);
        }
        mix(r, thump(95, 48, 10, len, decay * 0.7, 0), 0, 0.6 * peak(r));
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** sparse crackle (fire, embers) */
    static double[] crackle(double len, Curve density, double fLo, double fHi) {
        double[] r = buf(len);
        double t = 0;
        while (true) {
            double d = Math.max(0.5, density.at(t));
            t += -Math.log(1 - rnd.nextDouble()) / d;
            if (t >= len) break;
            if (density.at(t) < 0.5) continue;
            int o = ns(t), L = ns(rr(0.0003, 0.0025));
            double a = 0.15 + 0.85 * Math.pow(rnd.nextDouble(), 1.7);
            for (int i = 0; i < L && o + i < r.length; i++) r[o + i] += a * (rnd.nextDouble() * 2 - 1) * (1 - i / (double) L);
        }
        r = lp(hp(r, fLo), fHi);
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** roaring flame jet: noise with turbulent modulation of level and brightness */
    static double[] fireRoar(double len, Curve amp, Curve bright) {
        int n = ns(len);
        double[] a = rmsTo(pink(n), 1), b = rmsTo(brown(n), 1);
        Curve w1 = wobble(6), w2 = wobble(17), w3 = wobble(43);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = a[i] * 0.7 + b[i] * 0.8;
        x = hp(x, 45);
        double[] y = rmsTo(lp(x, t -> bright.at(t) * (1 + 0.35 * w1.at(t)), 0.9), 1);
        double[] body = rmsTo(bp(x, t -> 360 * (1 + 0.5 * w2.at(t)), 1.6), 0.55);
        double[] hiss = rmsTo(hp(white(n), 4500), 0.07);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            double turb = 1 + 0.42 * w1.at(t) + 0.28 * w2.at(t) + 0.16 * w3.at(t);
            r[i] = (y[i] + body[i] + hiss[i]) * Math.max(0.1, turb) * amp.at(t);
        }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** Minnaert bubble: rising sine with fast decay */
    static void bubble(double[] r, double at, double a, double f0, double dec) {
        int o = ns(at), L = ns(dec * 5);
        double ph = 0;
        for (int i = 0; i < L && o + i < r.length; i++) {
            if (o + i < 0) continue;
            double t = i / (double) SR;
            ph += 2 * Math.PI * f0 * (1 + 0.7 * t / dec) / SR;
            r[o + i] += a * Math.sin(ph) * env(t, 0.0015, dec);
        }
    }

    static double[] bubbles(double len, Curve density, double fLo, double fHi, double dLo, double dHi) {
        double[] r = buf(len);
        double t = 0;
        while (true) {
            double d = Math.max(0.5, density.at(t));
            t += -Math.log(1 - rnd.nextDouble()) / d;
            if (t >= len) break;
            if (density.at(t) < 0.5) continue;
            bubble(r, t, rr(0.2, 1), fLo * Math.pow(fHi / fLo, rnd.nextDouble()), rr(dLo, dHi));
        }
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** blood drops slapping the floor */
    static double[] splats(double len, Curve density) {
        double[] r = buf(len);
        double t = 0;
        while (true) {
            double d = Math.max(0.5, density.at(t));
            t += -Math.log(1 - rnd.nextDouble()) / d;
            if (t >= len) break;
            if (density.at(t) < 0.5) continue;
            int o = ns(t);
            double a = rr(0.2, 1), dec = rr(0.003, 0.012), cut = Math.exp(-2 * Math.PI * rr(900, 3000) / SR), y = 0;
            int L = ns(dec * 6);
            for (int i = 0; i < L && o + i < r.length; i++) {
                y = (1 - cut) * (rnd.nextDouble() * 2 - 1) + cut * y;
                r[o + i] += a * y * 3 * env(i / (double) SR, 0.0005, dec);
            }
            bubble(r, t + 0.002, a * 0.35, rr(450, 1400), rr(0.006, 0.02));
        }
        fadeOut(r, 0.01);
        return norm(r, 1);
    }

    /** wet gush: low noise through a wobbling resonance */
    static double[] gush(double len, Curve amp, double base) {
        int n = ns(len);
        Curve w = wobble(9), w2 = wobble(23);
        double[] x = pink(n);
        double[] a = rmsTo(lp(x, 1100), 1);
        double[] b = rmsTo(bp(x, t -> base * (1 + 0.6 * w.at(t) + 0.25 * w2.at(t)), 2.5), 0.9);
        double[] spray = rmsTo(hp(white(n), 2800), 0.12);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            r[i] = (a[i] + b[i] + spray[i]) * amp.at(t) * (1 + 0.35 * w2.at(t));
        }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** stone grinding: irregular stick-slip grains + continuous friction noise through resonances */
    static double[] grind(double len, Curve amp, Curve rate, double centre, double q) {
        int n = ns(len);
        double[] imp = new double[n];
        double t = 0;
        while (t < len) {
            t += Math.max(0.002, (1 / rate.at(t)) * (1 + 0.45 * g()));
            int o = ns(t), L = ns(rr(0.0015, 0.006));
            double a = rr(0.3, 1);
            for (int i = 0; i < L && o + i < n; i++) imp[o + i] += a * (rnd.nextDouble() * 2 - 1) * (1 - i / (double) L);
        }
        double[] cont = pink(n);
        Curve wc = wobble(9);
        rmsTo(imp, 1);
        rmsTo(cont, 0.5);
        double[] x = new double[n];
        for (int i = 0; i < n; i++) x[i] = imp[i] + cont[i] * (0.6 + 0.4 * wc.at(i / (double) SR));
        Curve wf = wobble(3);
        double[] a = rmsTo(bp(x, tt -> centre * (1 + 0.25 * wf.at(tt)), q), 1);
        double[] b = rmsTo(bp(x, tt -> centre * 2.3 * (1 + 0.2 * wf.at(tt + 1)), q * 1.5), 0.5);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (a[i] + b[i]) * amp.at(i / (double) SR);
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** heavy creak: slow stick-slip pulse train through resonant modes */
    static double[] creak(double len, Curve rate, Curve amp, double[] res, double q) {
        int n = ns(len);
        double[] p = new double[n];
        double t = 0.0;
        while (t < len) {
            t += 1 / rate.at(t) * (1 + 0.12 * g());
            int o = ns(t);
            double a = amp.at(t) * rr(0.6, 1);
            if (o + 2 < n) { p[o] += a * 0.5; p[o + 1] += a; p[o + 2] += a * 0.5; }
        }
        double[] r = new double[n];
        Curve w = wobble(1.5);
        for (int j = 0; j < res.length; j++) {
            final double f = res[j];
            final int jj = j;
            double[] y = bp(p, tt -> f * (1 + 0.04 * w.at(tt + jj)), q);
            for (int i = 0; i < n; i++) r[i] += y[i] / (1 + jj * 0.6) * (jj % 2 == 0 ? 1 : -1);
        }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** one chain link clink (small metal partials + optional low link clunk) */
    static void clink(double[] r, double at, double a, double heavy) {
        int o = ns(at);
        if (o >= r.length || o < 0) return;
        double f1 = rr(1700, 3200), f2 = f1 * rr(1.9, 2.4), f3 = f1 * rr(3.1, 3.9), d = rr(0.012, 0.05), fl = rr(330, 650);
        double p1 = rr(0, 6.28), p2 = rr(0, 6.28), p3 = rr(0, 6.28);
        int L = Math.min(r.length - o, ns(d * 7));
        for (int i = 0; i < L; i++) {
            double t = i / (double) SR, e = env(t, 0.0003, d);
            r[o + i] += a * (e * (Math.sin(2 * Math.PI * f1 * t + p1) * 0.6 + sinIf(f2, t, p2) * 0.4 + sinIf(f3, t, p3) * 0.25 * Math.exp(-t / (d * 0.4)))
                    + heavy * Math.sin(2 * Math.PI * fl * t) * env(t, 0.0005, d * 1.3) * 0.8
                    + (rnd.nextDouble() * 2 - 1) * 0.4 * env(t, 0.0002, 0.0008));
        }
    }

    /** rattling chains: jolts, each producing a burst of link impacts */
    static double[] chains(double len, Curve shake, double jolts, double heavy) {
        double[] r = buf(len);
        double t = 0;
        while (true) {
            t += -Math.log(1 - rnd.nextDouble()) / jolts;
            if (t >= len) break;
            double s = shake.at(t);
            if (s <= 0.02) continue;
            int cnt = 3 + rnd.nextInt(8);
            double tt = t;
            for (int c = 0; c < cnt; c++) {
                tt += rr(0.004, 0.03);
                clink(r, tt, s * rr(0.3, 1) * Math.pow(0.86, c), heavy);
            }
        }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** low rumble: brown noise, low-passed, with slow tremble */
    static double[] rumble(double len, double cutoff, Curve amp) {
        int n = ns(len);
        double[] r = lp(lp(brown(n), cutoff), cutoff);
        Curve w = wobble(7);
        for (int i = 0; i < n; i++) { double t = i / (double) SR; r[i] *= amp.at(t) * (1 + 0.35 * w.at(t)); }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    /** three detuned throats (Cerberus heads), each with its own roughness and vocal-tract size */
    static double[] throats(double len, double[] f0s, double[] sizes, Curve contour, Curve amp, double jit, double sub,
                            double breath, double rough, DoubleFunction<Vow> vow, double[] gains) {
        double[] r = new double[ns(len)];
        for (int h = 0; h < f0s.length; h++) {
            final double base = f0s[h];
            Curve dr = wobble(3 + h), a2 = wobble(2.5 + h * 0.7);
            final double lag = h * 0.011;
            Voice v = new Voice(t -> base * contour.at(t - lag) * (1 + 0.04 * dr.at(t)));
            v.amp = t -> amp.at(t - lag) * (0.65 + 0.35 * a2.at(t));
            v.jitter = k(jit); v.shimmer = k(0.2); v.sub = k(sub); v.subT = 0.08; v.ret = 0.01; v.fry = 0.03;
            double[] x = voice(len, v, vow.apply(sizes[h]), breath, gains, 1.4, 0.4);
            if (rough > 0) roughAM(x, 20 + h * 4.5, rough, 0.5);
            mix(r, x, 0, 1.0 / (1 + h * 0.25));
        }
        return norm(r, 1);
    }

    // ================================================================ MINOTAUR voice
    static double[] demon(double len, Curve f0, Curve amp, Vow vw, double jit, double rough) {
        Voice d = new Voice(t -> f0.at(t) * 0.5);
        d.amp = amp; d.jitter = k(jit); d.shimmer = k(0.25); d.sub = k(0.6); d.subT = 0.08; d.ret = 0.008; d.fry = 0.04;
        double[] x = voice(len, d, vw, 0.6, G_BEAST, 1.6, 0.8);
        roughAM(x, 26, rough, 0.5);
        return norm(x, 1);
    }

    static double[] minotaurRoar(int var) {
        double len = 2.65;
        Curve base = var == 0
                ? curve(0, 52, 0.2, 68, 0.55, 84, 1.3, 86, 1.85, 74, 2.3, 54, 2.65, 42)
                : curve(0, 66, 0.22, 58, 0.45, 76, 0.95, 90, 1.5, 86, 2.0, 70, 2.4, 50, 2.65, 40);
        Curve dr = wobble(4);
        Curve f0 = t -> base.at(t) * (1 + 0.03 * dr.at(t) + 0.012 * Math.sin(2 * Math.PI * 5.2 * t) * clamp((t - 0.6) / 0.6, 0, 1));
        Curve amp = var == 0 ? curve(0, 0, 0.14, 0.5, 0.5, 1, 1.6, 0.95, 2.15, 0.55, 2.65, 0)
                : curve(0, 0, 0.07, 0.75, 0.22, 0.45, 0.6, 1, 1.7, 0.9, 2.2, 0.5, 2.65, 0);
        Voice v = new Voice(f0);
        v.amp = amp; v.jitter = curve(0, 0.02, 0.6, 0.012, 1.9, 0.02, 2.65, 0.07); v.shimmer = k(0.12);
        v.sub = curve(0, 0.45, 0.5, 0.22, 1.7, 0.3, 2.4, 0.75); v.ret = 0.006; v.oq = 0.55;
        Vow vw = var == 0 ? new Vow(0.58, 0.0, "mm", 0.22, "oo", 0.5, "oh", 0.95, "aa", 1.75, "aa", 2.2, "aw", 2.65, "uh")
                : new Vow(0.58, 0.0, "uh", 0.25, "oh", 0.6, "aa", 1.45, "ah", 2.0, "aw", 2.65, "oo");
        double[] main = voice(len, v, vw, 0.35, G_BEAST, 1.3, 0.3);
        double[] dem = demon(len, f0, amp, new Vow(0.47, 0.0, "uh", 1.0, "aw", 2.65, "oo"), 0.045, 0.55);
        double[] s = buf(len + 0.15);
        mix(s, breath(0.2, 800, 0.8, curve(0, 0, 0.12, 1, 0.2, 0)), 0, 0.22);   // sharp intake
        mix(s, main, 0.1, 1.0);
        mix(s, dem, 0.1, 0.62);
        mix(s, breath(0.6, 650, 0.9, curve(0, 0, 0.1, 1, 0.6, 0)), 0.1 + len - 0.45, 0.25);  // exhale at the end
        s = beastPost(s, 2.3, 1700, 6);
        return reverb(s, 1.35, 0.84, 0.4, 0.3, 1.0, 0.03);
    }

    static double[] minotaurSnort(int var) {
        double[] s = buf(0.9);
        mix(s, blast(0.32, var == 0 ? 0.06 : 0.075, 34), 0, 0.75);
        mix(s, blast(0.5, var == 0 ? 0.11 : 0.13, 41), var == 0 ? 0.26 : 0.3, 1.0);
        return reverb(s, 1.1, 0.78, 0.4, 0.18, 0.3, 0.015);
    }

    static double[] minotaurGrunt(int var) {
        double len = 0.32 + var * 0.025;
        double[] fs = {80, 70, 88};
        double fb = fs[var];
        Curve f0 = curve(0, fb * 1.1, 0.08, fb, len, fb * 0.75);
        Curve amp = curve(0, 0, 0.025, 1, 0.14, 0.8, len - 0.06, 0.25, len, 0);
        Voice v = new Voice(f0);
        v.amp = amp; v.jitter = k(0.03); v.shimmer = k(0.15); v.sub = k(0.45); v.subT = 0.06; v.ret = 0.01; v.oq = 0.5;
        String[] vo = {"uh", "ah", "aw"};
        double[] main = voice(len, v, new Vow(0.58, 0.0, "er", 0.06, vo[var], len, "uh"), 0.5, G_BEAST, 1.4, 0.5);
        double[] dem = demon(len, f0, amp, new Vow(0.47, 0.0, "uh", len, "aw"), 0.05, 0.5);
        double[] s = buf(len + 0.08);
        mix(s, breath(0.1, 900, 0.8, curve(0, 0, 0.012, 1, 0.1, 0)), 0, 0.45);   // huff
        mix(s, main, 0.035, 1.0);
        mix(s, dem, 0.035, 0.5);
        s = beastPost(s, 2.6, 1600, 5);
        return reverb(s, 1.1, 0.72, 0.4, 0.16, 0.2, 0.015);
    }

    static double[] minotaurHurt(int var) {
        double len = 0.6;
        Curve f0 = var == 0 ? curve(0, 105, 0.06, 150, 0.22, 138, 0.45, 96, 0.6, 78)
                : curve(0, 125, 0.05, 165, 0.15, 150, 0.3, 140, 0.5, 92, 0.6, 80);
        Curve amp = curve(0, 0, 0.015, 1, 0.2, 0.85, 0.45, 0.4, 0.6, 0);
        Voice v = new Voice(f0);
        v.amp = amp; v.jitter = k(0.03); v.shimmer = k(0.14); v.sub = curve(0, 0.2, 0.3, 0.35, 0.6, 0.6); v.ret = 0.006; v.oq = 0.45;
        Vow vw = var == 0 ? new Vow(0.6, 0.0, "ah", 0.1, "aa", 0.35, "ae", 0.6, "uh") : new Vow(0.6, 0.0, "eh", 0.08, "aa", 0.4, "aw", 0.6, "uh");
        double[] main = voice(len, v, vw, 0.4, G_BEAST, 1.3, 0.35);
        double[] dem = demon(len, f0, amp, new Vow(0.48, 0.0, "aa", len, "uh"), 0.05, 0.5);
        double[] s = buf(len + 0.05);
        mix(s, main, 0.005, 1.0);
        mix(s, dem, 0.005, 0.4);
        s = beastPost(s, 2.6, 1900, 5);
        return reverb(s, 1.2, 0.78, 0.4, 0.2, 0.3, 0.02);
    }

    static double[] minotaurDeath() {
        double len = 3.2;
        Curve base = curve(0, 84, 0.3, 100, 0.8, 95, 1.6, 80, 2.3, 60, 2.9, 44, 3.2, 36);
        Curve dr = wobble(4);
        Curve f0 = t -> base.at(t) * (1 + 0.035 * dr.at(t) + 0.02 * Math.sin(2 * Math.PI * 4.5 * t) * clamp((t - 1.0) / 1.0, 0, 1));
        Curve amp = curve(0, 0, 0.12, 0.8, 0.4, 1, 1.4, 0.85, 2.3, 0.55, 2.9, 0.28, 3.2, 0);
        Voice v = new Voice(f0);
        v.amp = amp; v.jitter = curve(0, 0.015, 1.2, 0.02, 2.4, 0.05, 3.2, 0.1); v.shimmer = curve(0, 0.1, 2.0, 0.15, 3.2, 0.3);
        v.sub = curve(0, 0.3, 1.2, 0.3, 2.4, 0.6, 3.2, 0.9); v.ret = 0.012; v.oq = 0.55; v.fry = 0.01;
        double[] main = voice(len, v, new Vow(0.58, 0.0, "ah", 0.3, "aa", 1.2, "aa", 1.9, "aw", 2.5, "oh", 3.2, "mm"), 0.4, G_BEAST, 1.3, 0.45);
        double[] dem = demon(len, f0, amp, new Vow(0.47, 0.0, "aa", 1.5, "aw", 3.2, "oo"), 0.06, 0.6);
        double[] s = buf(4.7);
        mix(s, main, 0, 1.0);
        mix(s, dem, 0, 0.62);
        // last heavy breath with a wet rattle
        mix(s, breath(0.75, 520, 0.9, curve(0, 0, 0.12, 1, 0.75, 0)), 3.05, 0.4);
        Voice rat = new Voice(k(34));
        rat.amp = curve(0, 0, 0.1, 1, 0.5, 0); rat.jitter = k(0.12); rat.sub = k(0.8); rat.subT = 0.15; rat.fry = 0.2;
        mix(s, voice(0.5, rat, new Vow(0.5, 0.0, "uh", 0.5, "oo"), 0.8, G_BEAST, 1.8, 0.5), 3.1, 0.3);
        s = beastPost(s, 1.8, 1700, 5);
        // the body hits the floor, armour clatters
        double tt = 3.92;
        mix(s, thump(72, 30, 9, 0.75, 0.3, 0.4), tt, 1.3);
        mix(s, noiseHit(0.5, 30, 300, 0.002, 0.12), tt, 0.9);
        mix(s, crunch(0.3, 1.5, 1300, 0.07), tt, 0.3);
        mix(s, clang(310, 0.6, 0.25, 0.55), tt + 0.012, 0.22);
        mix(s, chains(0.5, curve(0, 1, 0.3, 0.3, 0.5, 0), 22, 0.6), tt + 0.01, 0.18);
        mix(s, debris(0.6, 50, 0.2, 1200, 5000), tt + 0.02, 0.2);
        return reverb(s, 1.35, 0.84, 0.4, 0.28, 0.9, 0.03);
    }

    static double[] minotaurStep(int var) {
        double[] s = buf(0.45);
        // hard hoof click
        double base = rr(2200, 2900);
        double[] click = buf(0.05);
        pebble(click, 0, 1, base, base * 1.6);
        pebble(click, 0.0015, 0.6, base * 1.3, base * 2.2);
        mix(s, click, 0, 0.55);
        mix(s, noiseHit(0.03, 1500, 0, 0.0003, 0.003), 0, 0.5);
        // weight
        mix(s, thump(95 + var * 8, 42, 25, 0.4, 0.075, 0), 0, 1.0);
        mix(s, noiseHit(0.2, 30, 260, 0.001, 0.045), 0, 0.7);
        mix(s, crunch(0.12, 1.2, 1800, 0.035), 0, 0.22);
        // armour jingle
        double t = 0.03;
        int cnt = 4 + rnd.nextInt(3);
        double[] jingle = buf(0.4);
        for (int c = 0; c < cnt; c++) { t += rr(0.012, 0.04); clink(jingle, t, rr(0.4, 1) * Math.pow(0.8, c), 0.25); }
        mix(s, norm(jingle, 1), 0, 0.14);
        mix(s, clang(rr(620, 820), 0.3, 0.06, 0.5), 0.04, 0.08);
        return reverb(s, 1.1, 0.78, 0.45, 0.18, 0.25, 0.012);
    }

    static double[] axeSwing(int var) {
        double[] len = {0.62, 0.7, 0.58}, peakT = {0.33, 0.4, 0.27}, fp = {950, 820, 1100};
        double[] w = whoosh(len[var], peakT[var], 240, fp[var], 300, 1.1, 0.12, 2.6);
        double[] s = buf(len[var] + 0.05);
        mix(s, w, 0, 1);
        // the heavy blade displaces air: a soft low push at the closest point
        mix(s, noiseHit(0.35, 30, 160, 0.08, 0.08), peakT[var] - 0.1, 0.5);
        return reverb(s, 1.2, 0.76, 0.5, 0.12, 0.2, 0.01);
    }

    static double[] axeImpact(int var) {
        double[] s = buf(1.7);
        mix(s, thump(var == 0 ? 110 : 96, 34, 9, 1.3, 0.33, 0.5), 0, 1.3);           // sub boom
        mix(s, noiseHit(0.6, 30, 200, 0.001, 0.12), 0, 0.9);                          // body of the blow
        mix(s, clang(var == 0 ? 430 : 365, 1.6, 0.95, 0.68), 0.001, 0.55);            // the blade rings
        mix(s, clang(var == 0 ? 1170 : 990, 0.8, 0.35, 0.55), 0.0015, 0.22);
        mix(s, noiseHit(0.05, 2500, 0, 0.0002, 0.006), 0, 0.6);                       // crack
        mix(s, crunch(0.45, 3.0, 1500, 0.09), 0, 0.7);                                // stone breaks
        mix(s, crunch(0.3, 2.0, 3200, 0.05), 0.005, 0.3);
        mix(s, debris(1.5, 80, 0.35, 1200, 5500), 0.025, 0.35);                       // chips raining down
        double[] dust = pink(ns(1.2));
        dust = hp(lp(dust, 2200), 300);
        rmsTo(dust, 0.3);
        for (int i = 0; i < dust.length; i++) dust[i] *= env(i / (double) SR, 0.02, 0.35);
        fadeOut(dust, 0.05);
        mix(s, dust, 0.01, 0.35);
        return reverb(s, 1.4, 0.83, 0.4, 0.3, 0.8, 0.02);
    }

    /** whirling sweep: 5 whooshes per file, file starts and ends in a trough so back-to-back plays continue the rhythm */
    static double[] axeSpin() {
        double P = 0.28, len = 5 * P;
        int n = ns(len);
        Curve phase = t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / P);
        Curve fc = t -> 300 + 750 * Math.pow(phase.at(t), 1.5);
        Curve e = t -> 0.12 + 0.88 * Math.pow(phase.at(t), 2.4);
        double[] a = rmsTo(bp(pink(n), fc, 1.2), 1);
        double[] b = rmsTo(bp(pink(n), t -> fc.at(t) * 2.4, 1.6), 0.45);
        double[] c = rmsTo(bp(white(n), t -> fc.at(t) * 3.3, 14), 0.12);
        double[] lo = rmsTo(lp(pink(n), t -> fc.at(t) * 0.35, 0.7), 0.6);
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = (a[i] + b[i] + c[i] + lo[i]) * e.at(i / (double) SR);
        return r;
    }

    static double[] charge() {
        double[] s = buf(2.15);
        // pawing: hoof scrapes over grit
        double[] pawT = {0.0, 0.36, 0.7};
        for (double pt : pawT) {
            double[] scr = breath(0.18, 1600, 0.8, curve(0, 0, 0.02, 1, 0.18, 0));
            mix(s, scr, pt, 0.35);
            mix(s, debris(0.2, 300, 0.08, 1500, 6000), pt + 0.01, 0.3);
            mix(s, thump(110, 55, 25, 0.2, 0.05, 0.3), pt, 0.5);
        }
        mix(s, minotaurSnort(0), 0.95, 0.75);
        // rising stampede rumble
        mix(s, rumble(1.6, 110, curve(0, 0, 0.6, 0.35, 1.5, 1, 1.6, 0.9)), 0.55, 0.85);
        mix(s, rumble(1.3, 260, curve(0, 0, 0.8, 0.4, 1.3, 0.7)), 0.85, 0.35);
        // first gallop strides
        double[] strides = {1.3, 1.55, 1.73, 1.87, 1.98, 2.07};
        for (int i = 0; i < strides.length; i++) {
            double a = 0.55 + i * 0.07;
            mix(s, thump(90, 40, 22, 0.25, 0.06, 0.4), strides[i], a);
            mix(s, crunch(0.1, 1.0, 1700, 0.03), strides[i], a * 0.25);
        }
        double[] r = reverb(s, 1.3, 0.8, 0.4, 0.22, 0.4, 0.02);
        return Arrays.copyOf(r, ns(2.15));
    }

    static double[] wallRise() {
        double len = 2.0;
        double[] s = buf(len);
        mix(s, thump(85, 34, 8, 0.8, 0.25, 0.4), 0, 0.8);                                     // floor breaks open
        mix(s, crunch(0.35, 2.5, 1300, 0.09), 0, 0.5);
        mix(s, rumble(len, 90, curve(0, 0.2, 0.25, 0.85, 1.4, 1, 1.72, 0.6, 2.0, 0)), 0, 0.9);
        mix(s, grind(1.75, curve(0, 0, 0.25, 0.9, 1.3, 1, 1.6, 0.4, 1.75, 0), curve(0, 30, 1.6, 55), 420, 2.2), 0.05, 0.5);
        mix(s, grind(1.65, curve(0, 0, 0.3, 0.7, 1.3, 0.8, 1.65, 0), k(80), 1350, 3), 0.1, 0.22);
        for (int i = 0; i < 7; i++) {
            double at = rr(0.15, 1.5);
            mix(s, crunch(0.15, 2.0, rr(1200, 2600), 0.03), at, rr(0.15, 0.35));
            mix(s, noiseHit(0.02, 2500, 0, 0.0002, 0.003), at, rr(0.1, 0.25));
        }
        mix(s, debris(len, 25, 1.5, 1500, 6000), 0.05, 0.2);
        mix(s, thump(90, 40, 15, 0.35, 0.11, 0.3), 1.62, 0.75);                             // walls lock in place
        mix(s, crunch(0.2, 2.0, 1500, 0.05), 1.62, 0.35);
        double[] r = reverb(s, 1.35, 0.8, 0.4, 0.25, 0.5, 0.02);
        return r;
    }

    static double[] voiceMinotaur(int var) {
        double[] fs = {74, 82, 66, 90, 70, 60};
        String[][] vo = {{"uh", "aa"}, {"oo", "oh"}, {"ah", "aw"}, {"eh", "ah"}, {"oh", "uh"}, {"aw", "oo"}};
        String[] cons = {"mm", "er", "mm", "er", "uh", "mm"};
        double len = 0.16 - (var % 3) * 0.012;
        double fb = fs[var];
        Curve f0 = curve(0, fb * 1.06, 0.05, fb, len, fb * 0.9);
        Curve amp = curve(0, 0, 0.012, 1, len - 0.05, 0.85, len - 0.006, 0, len, 0);
        Voice v = new Voice(f0);
        v.amp = amp; v.jitter = k(0.025); v.shimmer = k(0.1); v.sub = k(0.35); v.ret = 0.012; v.oq = 0.55;
        double[] main = voice(len, v, new Vow(0.6, 0.0, cons[var], 0.035, vo[var][0], len, vo[var][1]), 0.3, G_BEAST, 1.3, 0.4);
        double[] dem = demon(len, f0, amp, new Vow(0.48, 0.0, "uh", len, "aw"), 0.04, 0.4);
        double[] s = buf(len);
        mix(s, main, 0, 1);
        mix(s, dem, 0, 0.35);
        s = beastPost(s, 2.0, 1700, 5);
        fadeOut(s, 0.012);
        return s;
    }

    // ================================================================ CERBERUS
    static DoubleFunction<Vow> snarlVow(double len, String a, String b, String c) {
        return sz -> new Vow(sz, 0.0, a, len * 0.5, b, len, c);
    }

    /** lip-curl texture: bright fluttering noise + saliva clicks */
    static double[] lipCurl(double len, Curve amp) {
        int n = ns(len);
        double[] x = rmsTo(lp(hp(white(n), 2000), 6500), 1);
        roughAM(x, 31, 0.85, 0.4);
        amp(x, amp);
        double[] clicks = crackle(len, k(9), 2500, 7000);
        for (int i = 0; i < n; i++) clicks[i] *= amp.at(i / (double) SR);
        rmsTo(clicks, rms(x) * 0.6);
        for (int i = 0; i < n; i++) x[i] += clicks[i];
        fadeOut(x, 0.02);
        return norm(x, 1);
    }

    static double[] cerberusGrowl(int var) {
        double len = var == 0 ? 1.4 : 1.35;
        Curve amp = var == 0 ? curve(0, 0, 0.2, 0.7, 0.5, 1, 0.8, 0.7, 1.08, 0.95, 1.4, 0)
                : curve(0, 0, 0.3, 0.5, 0.8, 0.85, 1.1, 1, 1.35, 0);
        Curve contour = var == 0 ? curve(0, 0.95, 0.6, 1.05, 1.4, 0.95) : curve(0, 0.9, 1.1, 1.12, 1.35, 1.0);
        double[] f0s = var == 0 ? new double[]{58, 67, 79} : new double[]{62, 71, 84};
        double[] x = throats(len, f0s, new double[]{0.62, 0.68, 0.75}, contour, amp, 0.06, 0.5, 0.65, 0.5,
                snarlVow(len, "uh", "ah", "aw"), G_DOG);
        double[] s = buf(len + 0.1);
        mix(s, x, 0, 1);
        mix(s, lipCurl(len, amp), 0, 0.16);
        if (var == 1) mix(s, breath(0.15, 1400, 0.9, curve(0, 0, 0.05, 1, 0.15, 0)), len - 0.1, 0.3);   // sharp breath in
        s = beastPost(s, 2.4, 2200, 4);
        return reverb(s, 1.25, 0.78, 0.4, 0.2, 0.4, 0.02);
    }

    static double[] cerberusBark(int var) {
        double len = 0.32;
        double[] fs = {185, 165, 205};
        double fb = fs[var];
        Curve contour = curve(0, 0.9, 0.035, 1.08, 0.12, 0.78, 0.32, 0.52);
        Curve amp = curve(0, 0, 0.008, 1, 0.06, 0.85, 0.18, 0.35, 0.32, 0);
        DoubleFunction<Vow> vw = sz -> new Vow(sz, 0.0, "oo", 0.03, "aa", 0.12, var == 1 ? "aw" : "ah", 0.32, "uh");
        double[] x = throats(len, new double[]{fb, fb * 0.76, fb * 1.19}, new double[]{0.64, 0.6, 0.7}, contour, amp, 0.05, 0.45, 0.6, 0.25, vw, G_DOG);
        double[] s = buf(len + 0.05);
        mix(s, noiseHit(0.04, 200, 2500, 0.0008, 0.01), 0, 0.5);       // plosive
        mix(s, x, 0.004, 1.0);
        mix(s, thump(120, 50, 25, 0.25, 0.06, 0), 0.004, 0.7);           // chest
        s = beastPost(s, 3.0, 2000, 4);
        return reverb(s, 1.2, 0.66, 0.45, 0.2, 0.2, 0.02);
    }

    static double[] cerberusBite(int var) {
        double[] s = buf(0.45);
        double[] clack = buf(0.08);
        double b = var == 0 ? 2100 : 2450;
        pebble(clack, 0, 1, b, b * 1.5);
        pebble(clack, 0.0008, 0.8, b * 1.6, b * 2.6);
        pebble(clack, var == 0 ? 0.009 : 0.013, 0.7, b * 1.15, b * 2.0);   // second row of teeth
        pebble(clack, var == 0 ? 0.0095 : 0.0135, 0.5, b * 1.9, b * 3.0);
        mix(s, norm(clack, 1), 0, 0.8);
        mix(s, noiseHit(0.02, 2000, 0, 0.0002, 0.002), 0, 0.5);
        mix(s, thump(150, 60, 30, 0.2, 0.05, 0), 0.002, 0.7);              // jaw closing
        mix(s, crunch(0.2, 2.5, 1800, 0.05), 0.01, 0.45);                  // bone
        Curve w = wobble(14);
        double[] sq = rmsTo(bp(white(ns(0.3)), t -> 520 * (1 + 0.5 * w.at(t)), 3.5), 0.3);
        for (int i = 0; i < sq.length; i++) sq[i] *= env(i / (double) SR, 0.008, 0.08);
        fadeOut(sq, 0.02);
        mix(s, sq, 0.015, 0.9);                                             // wet flesh
        mix(s, bubbles(0.3, curve(0, 40, 0.2, 10, 0.3, 0), 300, 900, 0.006, 0.02), 0.02, 0.15);
        return reverb(s, 1.0, 0.72, 0.45, 0.14, 0.2, 0.01);
    }

    static double[] cerberusHowl() {
        double len = 2.9;
        Curve base = curve(0, 165, 0.5, 235, 1.4, 268, 2.2, 245, 2.9, 145);
        double[] ratios = {1.0, 1.06, 0.89};      // a semitone above, a whole tone below: dissonant cluster
        double[] lags = {0, 0.07, 0.13};
        double[] s = buf(len + 0.2);
        double[] cluster = new double[ns(len + 0.2)];
        for (int h = 0; h < 3; h++) {
            final double r = ratios[h], lag = lags[h], vr = 5.2 + h * 0.4;
            Curve dr = wobble(1.5), vib = wobble(6 + h);
            Voice v = new Voice(t -> base.at(t - lag) * r * (1 + 0.012 * dr.at(t) + 0.008 * Math.sin(2 * Math.PI * vr * t) + 0.003 * vib.at(t)));
            v.amp = curve(0, 0, 0.35, 0.8, 1.0, 1, 2.2, 0.85, 2.75, 0.2, 2.9, 0);
            v.jitter = k(0.006); v.shimmer = k(0.03); v.sub = curve(0, 0, 2.2, 0, 2.9, 0.35); v.oq = 0.65; v.sq = 0.62; v.ret = 0.06;
            double[] x = voice(len, v, new Vow(0.72 + h * 0.04, 0.0, "oo", 0.5, "oh", 1.5, "aw", 2.3, "oh", 2.9, "oo"), 0.1, G_HOWL, 0.8, 0.1);
            mix(cluster, x, lags[h], 1.0 / (1 + h * 0.3));
        }
        norm(cluster, 1);
        mix(s, cluster, 0, 1);
        // ring modulation at the golden ratio of the pitch: inharmonic, metallic sidebands
        double ph = 0;
        double[] ring = new double[cluster.length];
        for (int i = 0; i < ring.length; i++) {
            double t = i / (double) SR;
            ph += 2 * Math.PI * base.at(t) * 1.618 / SR;
            ring[i] = cluster[i] * Math.sin(ph);
        }
        mix(s, ring, 0, 0.3);
        // high shimmer: three sines two octaves up, trembling
        double[] sh = new double[cluster.length];
        double[] p = new double[3];
        Curve tr = wobble(11);
        Curve shAmp = curve(0, 0, 0.6, 0, 1.3, 1, 2.3, 0.8, 2.9, 0);
        for (int i = 0; i < sh.length; i++) {
            double t = i / (double) SR, v = 0;
            for (int h = 0; h < 3; h++) { p[h] += 2 * Math.PI * base.at(t - lags[h]) * ratios[h] * 4.01 / SR; v += Math.sin(p[h]); }
            sh[i] = v * shAmp.at(t) * (0.6 + 0.4 * tr.at(t));
        }
        mix(s, norm(sh, 1), 0, 0.15);
        mix(s, breath(0.4, 700, 0.8, curve(0, 0, 0.2, 1, 0.4, 0)), 0, 0.08);
        s = hp(hp(s, 75), 75);
        s = lp(s, 8000);
        sat(s, 1.3);
        return reverb(s, 1.6, 0.88, 0.45, 0.5, 1.4, 0.035);
    }

    static double[] cerberusFlame() {
        double len = 2.45;
        double[] s = buf(len);
        mix(s, fireBurstCore(0.5), 0, 0.7);
        mix(s, fireRoar(2.35, curve(0, 0, 0.1, 0.85, 0.3, 1, 1.9, 0.95, 2.35, 0), curve(0, 700, 0.2, 3200, 1.8, 2700, 2.35, 900)), 0.05, 1.0);
        Voice v = new Voice(t -> 44);
        Curve dr = wobble(4);
        v.f0 = t -> 44 * (1 + 0.08 * dr.at(t));
        v.amp = curve(0, 0, 0.2, 1, 2.0, 0.9, 2.35, 0); v.jitter = k(0.08); v.sub = k(0.7); v.subT = 0.1; v.fry = 0.1;
        double[] throat = voice(2.35, v, new Vow(0.6, 0.0, "aa", 2.35, "ah"), 0.4, G_BEAST, 1.6, 1.0);
        mix(s, lp(throat, 500), 0.05, 0.45);
        mix(s, crackle(len, curve(0, 0, 0.3, 12, 1.5, 40, 2.4, 15), 1500, 6500), 0, 0.3);
        sat(s, 1.5);
        return reverb(s, 1.3, 0.8, 0.45, 0.2, 0.4, 0.02);
    }

    static double[] cerberusHurt(int var) {
        double len = 0.4;
        Curve contour = var == 0 ? curve(0, 0.7, 0.04, 1.0, 0.12, 0.86, 0.28, 0.5, 0.4, 0.38)
                : curve(0, 0.8, 0.03, 1.1, 0.09, 1.0, 0.17, 0.95, 0.32, 0.55, 0.4, 0.42);
        Curve amp = curve(0, 0, 0.01, 1, 0.12, 0.8, 0.3, 0.3, 0.4, 0);
        DoubleFunction<Vow> vw = sz -> new Vow(sz, 0.0, "ee", 0.05, "aa", 0.2, "aw", 0.4, "oo");
        double[] x = throats(len, new double[]{420, 330}, new double[]{0.7, 0.64}, contour, amp, 0.03, 0.25, 0.4, 0.15, vw, G_DOG);
        double[] sn = throats(len, new double[]{90}, new double[]{0.62}, curve(0, 1, 0.4, 0.85), curve(0, 0, 0.05, 1, 0.3, 0.5, 0.4, 0), 0.07, 0.6, 0.7, 0.5,
                snarlVow(len, "uh", "ah", "uh"), G_DOG);
        double[] s = buf(len + 0.05);
        mix(s, x, 0, 1);
        mix(s, sn, 0.02, 0.4);
        s = beastPost(s, 2.2, 2400, 3);
        return reverb(s, 1.2, 0.7, 0.45, 0.18, 0.25, 0.02);
    }

    static double[] cerberusWhimper() {
        double[] s = buf(1.6);
        double[] starts = {0, 0.46, 0.98};
        for (int w = 0; w < 3; w++) {
            double len = 0.36 - w * 0.03, top = 410 - w * 35;
            Curve dr = wobble(7);
            Curve base = curve(0, top * 0.8, 0.12, top, 0.26, top * 0.88, len, top * 0.7);
            Voice v = new Voice(t -> base.at(t) * (1 + 0.03 * dr.at(t)));
            v.amp = curve(0, 0, 0.05, 1, len - 0.12, 0.7, len, 0); v.jitter = k(0.015); v.shimmer = k(0.12); v.oq = 0.7; v.sq = 0.6; v.ret = 0.08;
            double[] x = voice(len, v, new Vow(0.74, 0.0, "mm", 0.1, "oo", len, "mm"), 0.85, G_HOWL, 1.0, 0.1);
            mix(s, x, starts[w], 1.0 - w * 0.15);
            if (w < 2) mix(s, breath(0.13, 1700, 1.2, curve(0, 0, 0.05, 1, 0.13, 0)), starts[w] + len + 0.02, 0.18);   // shaky inhale
        }
        s = lp(s, 7000);
        return reverb(s, 1.2, 0.78, 0.45, 0.18, 0.3, 0.02);
    }

    static double[] cerberusDeath() {
        double[] s = buf(3.4);
        // final howl, two heads, breaking
        Curve base = curve(0, 225, 0.25, 290, 0.8, 272, 1.35, 205, 1.75, 130);
        for (int h = 0; h < 2; h++) {
            final double r = h == 0 ? 1.0 : 1.06, lag = h * 0.06;
            Curve dr = wobble(5);
            Voice v = new Voice(t -> base.at(t - lag) * r * (1 + 0.015 * dr.at(t) + 0.012 * Math.sin(2 * Math.PI * 6 * t) * clamp(t - 0.6, 0, 1)));
            v.amp = curve(0, 0, 0.12, 0.8, 0.4, 1, 1.2, 0.8, 1.75, 0);
            v.jitter = curve(0, 0.006, 0.9, 0.01, 1.6, 0.05); v.shimmer = curve(0, 0.03, 1.75, 0.2); v.sub = curve(0, 0, 1.0, 0, 1.75, 0.5);
            v.oq = 0.68; v.sq = 0.62; v.ret = 0.09;
            mix(s, voice(1.75, v, new Vow(0.72 + h * 0.05, 0.0, "oo", 0.4, "oh", 1.2, "aw", 1.75, "uh"), 0.2, G_HOWL, 0.9, 0.1), lag, 0.8 - h * 0.25);
        }
        // collapsing into a growl
        double[] gr = throats(1.2, new double[]{62, 70, 81}, new double[]{0.62, 0.68, 0.75}, curve(0, 1.0, 1.2, 0.68),
                curve(0, 0, 0.25, 0.85, 0.7, 0.6, 1.2, 0), 0.08, 0.6, 0.7, 0.55, snarlVow(1.2, "ah", "uh", "oo"), G_DOG);
        mix(s, gr, 1.35, 0.75);
        s = beastPost(s, 1.6, 2200, 3);
        // heavy body thud, collars rattle
        double tt = 2.58;
        mix(s, thump(66, 28, 9, 0.75, 0.3, 0.4), tt, 1.3);
        mix(s, noiseHit(0.45, 30, 300, 0.002, 0.11), tt, 0.85);
        mix(s, crunch(0.25, 1.2, 1200, 0.06), tt, 0.25);
        mix(s, chains(0.5, curve(0, 1, 0.3, 0.3, 0.5, 0), 20, 0.5), tt + 0.01, 0.15);
        mix(s, debris(0.6, 40, 0.2, 1200, 5000), tt + 0.02, 0.15);
        return reverb(s, 1.4, 0.85, 0.42, 0.3, 0.8, 0.03);
    }

    static double[] cerberusStep(int var) {
        double[] s = buf(0.34);
        mix(s, thump(70 + var * 6, 36, 18, 0.3, 0.06, 0), 0, 1.0);          // padded paw
        mix(s, noiseHit(0.15, 30, 420, 0.003, 0.045), 0, 0.6);
        int claws = 2 + var % 2 + rnd.nextInt(2);
        for (int c = 0; c < claws; c++) {
            double at = 0.015 + c * rr(0.018, 0.035), dl = rr(0.03, 0.07);
            double[] sc = grind(dl + 0.02, curve(0, 0, 0.004, 1, dl, 0.3, dl + 0.02, 0), k(rr(140, 260)), rr(2600, 3600), 2.0);
            mix(s, sc, at, rr(0.12, 0.22));
            double[] tick = buf(0.03);
            pebble(tick, 0, 1, 3000, 6500);
            mix(s, tick, at, 0.12);
        }
        return reverb(s, 1.1, 0.76, 0.45, 0.15, 0.2, 0.01);
    }

    // ================================================================ ARENA
    static double[] gateOpen() {
        double len = 3.65;
        double[] s = buf(len);
        // latch releases
        mix(s, clang(240, 0.6, 0.25, 0.55), 0, 0.4);
        mix(s, thump(110, 50, 25, 0.3, 0.08, 0.5), 0, 0.6);
        // ratchet pawl over the winch teeth
        double t = 0.28;
        double[] rat = buf(len);
        while (t < 3.0) {
            double ra = curve(0.28, 7.5, 1.5, 9.5, 3.0, 8.5).at(t);
            t += 1 / ra * (1 + 0.06 * g());
            double[] tick = buf(0.06);
            double fb = rr(1700, 1900);
            pebble(tick, 0, 1, fb, fb * 2.8);
            pebble(tick, 0.0005, 0.7, fb * 2.1, fb * 3.4);
            mix(tick, thump(420, 330, 40, 0.06, 0.012, 0), 0, 0.5);
            mix(rat, tick, t, rr(0.8, 1));
        }
        mix(s, norm(rat, 1), 0, 0.3);
        mix(s, chains(len, curve(0, 0, 0.3, 0.7, 3.0, 0.65, 3.15, 0), 9, 0.7), 0, 0.35);
        // iron grinding in the stone grooves
        mix(s, grind(3.3, curve(0, 0, 0.5, 0.8, 2.9, 0.8, 3.2, 0), k(42), 620, 2), 0.2, 0.4);
        // heavy creak
        mix(s, creak(3.2, curve(0, 14, 0.8, 22, 1.6, 11, 2.4, 18, 3.2, 13), curve(0, 0, 0.4, 0.9, 1.2, 0.5, 1.7, 0.9, 2.5, 0.3, 3.0, 0),
                new double[]{310, 780, 1650, 2900}, 18), 0.25, 0.35);
        mix(s, rumble(3.2, 90, curve(0, 0, 0.5, 0.8, 2.9, 0.8, 3.2, 0)), 0.2, 0.5);
        // gate locks at the top
        double te = 3.15;
        mix(s, thump(80, 38, 12, 0.5, 0.17, 0.5), te, 1.0);
        mix(s, clang(180, 0.5, 0.35, 0.5), te + 0.003, 0.4);
        mix(s, chains(0.45, curve(0, 1, 0.3, 0.4, 0.45, 0), 24, 0.7), te + 0.02, 0.3);
        mix(s, debris(0.45, 40, 0.15, 1200, 5000), te + 0.02, 0.15);
        double[] r = reverb(s, 1.4, 0.83, 0.4, 0.3, 0.5, 0.02);
        return r;
    }

    static double[] chainsSound(int var) {
        double len = 1.0;
        Curve shake = var == 0 ? curve(0, 0.9, 0.25, 1, 0.6, 0.6, 0.9, 0.15, 1.0, 0) : curve(0, 0.4, 0.2, 1, 0.45, 0.5, 0.65, 0.9, 0.95, 0);
        double[] s = buf(len);
        mix(s, chains(len, shake, var == 0 ? 16 : 13, 0.9), 0, 1);
        return reverb(s, 1.25, 0.8, 0.45, 0.22, 0.3, 0.015);
    }

    /** whoomp of igniting flames */
    static double[] fireBurstCore(double len) {
        int n = ns(len);
        double[] w = pink(n);
        Curve tb = wobble(18);
        double[] y = rmsTo(lp(w, curve(0, 150, 0.06, 2600, 0.3, 900, len, 350), 1.2), 1);
        double[] r = new double[n];
        Curve a = curve(0, 0, 0.035, 1, 0.15, 0.7, 0.45 * len / 0.5, 0.25, len, 0);
        for (int i = 0; i < n; i++) { double t = i / (double) SR; r[i] = y[i] * a.at(t) * (1 + 0.4 * tb.at(t)); }
        // the low pressure "whoomp": sine gliding up then down
        double ph = 0;
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR, f = 40 + 55 * Math.exp(-Math.pow((t - 0.04) / 0.05, 2));
            ph += 2 * Math.PI * f / SR;
            r[i] += 1.2 * Math.sin(ph) * env(t, 0.02, 0.1);
        }
        fadeOut(r, 0.02);
        return norm(r, 1);
    }

    static double[] fireBurst() {
        double[] s = buf(1.25);
        mix(s, fireBurstCore(1.2), 0, 1);
        mix(s, fireRoar(1.1, curve(0, 0, 0.08, 0.6, 0.3, 0.4, 1.1, 0), curve(0, 2000, 1.1, 900)), 0.03, 0.45);
        mix(s, crackle(1.2, curve(0, 0, 0.08, 45, 0.6, 15, 1.2, 2), 1500, 6500), 0, 0.3);
        return reverb(s, 1.25, 0.8, 0.45, 0.2, 0.35, 0.015);
    }

    static double[] geyser() {
        double[] s = buf(1.65);
        mix(s, rumble(0.5, 100, curve(0, 0, 0.25, 1, 0.5, 0)), 0, 0.6);
        mix(s, thump(85, 35, 9, 0.6, 0.22, 0.3), 0.22, 0.9);
        mix(s, gush(1.2, curve(0, 0, 0.03, 1, 0.4, 0.75, 0.85, 0.3, 1.2, 0), 420), 0.22, 0.9);
        mix(s, bubbles(1.0, curve(0, 80, 0.6, 30, 1.0, 0), 220, 900, 0.008, 0.035), 0.24, 0.35);
        mix(s, splats(1.0, curve(0, 0, 0.25, 70, 0.7, 30, 1.0, 0)), 0.55, 0.45);
        mix(s, crunch(0.2, 2.0, 1300, 0.05), 0.22, 0.3);
        return reverb(s, 1.3, 0.8, 0.45, 0.2, 0.4, 0.015);
    }

    static double[] impactHeavy() {
        double[] s = buf(2.2);
        mix(s, thump(120, 28, 6, 1.7, 0.55, 0.6), 0, 1.3);           // sub drop
        mix(s, thump(180, 60, 25, 0.5, 0.12, 0), 0, 0.5);            // mid boom for small speakers
        mix(s, noiseHit(0.9, 25, 300, 0.001, 0.25), 0, 1.0);          // air blast
        mix(s, crunch(0.5, 3.0, 1200, 0.15), 0, 0.6);
        mix(s, crunch(0.3, 2.5, 2600, 0.06), 0.004, 0.3);
        mix(s, noiseHit(0.05, 2500, 0, 0.0002, 0.006), 0, 0.5);
        mix(s, debris(2.0, 70, 0.5, 900, 5000), 0.04, 0.4);
        mix(s, debris(1.6, 20, 0.6, 400, 1500), 0.08, 0.25);           // bigger rocks
        double[] dust = hp(lp(pink(ns(1.8)), 2000), 250);
        rmsTo(dust, 0.3);
        for (int i = 0; i < dust.length; i++) dust[i] *= env(i / (double) SR, 0.03, 0.6);
        fadeOut(dust, 0.1);
        mix(s, dust, 0.02, 0.3);
        return reverb(s, 1.45, 0.84, 0.4, 0.3, 0.8, 0.02);
    }

    static double[] stingerHit() {
        double[] hit = buf(2.6);
        mix(hit, thump(75, 28, 4, 2.4, 0.9, 0.3), 0, 1.0);
        mix(hit, thump(160, 55, 20, 0.6, 0.13, 0.4), 0, 0.6);
        mix(hit, noiseHit(1.2, 25, 260, 0.002, 0.25), 0, 0.8);
        mix(hit, clang(92, 2.6, 1.6, 0.72), 0.002, 0.45);
        mix(hit, clang(610, 1.4, 0.5, 0.6), 0.001, 0.22);
        mix(hit, crunch(0.3, 2.0, 1800, 0.07), 0, 0.25);
        return hit;
    }

    static double[] stinger() {
        double lead = 0.8;
        double[] hit = stingerHit();
        // reverse swell: the reverb of the hit itself, reversed
        // reverse swell: reversed decaying sources (metal + crash noise) plus the reversed reverb of the hit,
        // with the envelope forced to rise all the way into the hit (reverb build-up would leave a dip otherwise)
        int n = ns(lead);
        double[] src = buf(1.2);
        mix(src, clang(92, 1.2, 0.5, 0.75), 0, 0.6);
        double[] crash = lp(hp(pink(ns(1.2)), 300), 7000);
        rmsTo(crash, 0.35);
        for (int i = 0; i < crash.length; i++) crash[i] *= env(i / (double) SR, 0.001, 0.3);
        mix(src, crash, 0, 1.0);
        double[] wetOnly = reverb(Arrays.copyOf(hit, ns(0.4)), 1.6, 0.88, 0.25, 1.0, 1.2, 0.0, 0, 0);
        mix(src, norm(wetOnly, 1), 0, 0.6);
        double[] rv = reverse(src);
        double[] swell = Arrays.copyOfRange(rv, rv.length - n, rv.length);
        swell = shapeEnv(swell, t -> Math.pow(t / lead, 2.2), 8);
        fadeOut(swell, 0.004);
        norm(swell, 1);
        double[] riser = bp(pink(n), t -> 250 * Math.pow(14, t / lead), 1.4);
        rmsTo(riser, 0.3);
        for (int i = 0; i < n; i++) riser[i] *= Math.pow(i / (double) n, 2.5);
        double[] s = buf(lead + 2.6);
        mix(s, swell, 0, 0.55);
        mix(s, riser, 0, 0.35);
        double[] tail = reverb(hit, 1.8, 0.9, 0.55, 0.6, 1.0, 0.02, 2500, 1);
        // dark drone under the tail
        double[] dr = buf(2.4);
        for (int i = 0; i < dr.length; i++) {
            double t = i / (double) SR;
            dr[i] = (Math.sin(2 * Math.PI * 41.2 * t) + 0.6 * Math.sin(2 * Math.PI * 61.9 * t + 1) + 0.25 * Math.sin(2 * Math.PI * 82.6 * t)) * env(t, 0.15, 0.7);
        }
        fadeOut(dr, 0.3);
        mix(s, norm(tail, 1), lead, 1.0);
        mix(s, dr, lead, 0.22);
        sat(s, 1.3);
        return s;
    }

    static double[] heartbeat() {
        double[] s = buf(1.15);
        mix(s, thump(62, 33, 14, 0.5, 0.11, 0), 0, 1.0);
        mix(s, thump(140, 70, 30, 0.2, 0.035, 0), 0, 0.45);        // upper body of the beat, audible on small speakers
        mix(s, noiseHit(0.2, 40, 500, 0.004, 0.04), 0, 0.5);
        mix(s, thump(74, 38, 16, 0.45, 0.085, 0), 0.29, 0.8);
        mix(s, thump(160, 80, 30, 0.18, 0.03, 0), 0.29, 0.35);
        mix(s, noiseHit(0.18, 40, 500, 0.004, 0.035), 0.29, 0.4);
        s = lp(lp(s, 650), 650);
        double[] r = reverb(s, 0.9, 0.78, 0.6, 0.18, 0.5, 0.01);
        return r;
    }

    static double[] paralyze() {
        double[] s = buf(1.1);
        mix(s, thump(78, 32, 12, 0.6, 0.15, 0.2), 0, 1.0);
        mix(s, noiseHit(0.4, 25, 280, 0.01, 0.15), 0, 0.5);
        // tinnitus: high beating partials with a slow FM drift and trembling
        double[] fr = {3950, 4012, 5880, 7920}, am = {1, 0.75, 0.35, 0.15};
        double[] ring = buf(1.05);
        Curve tr = wobble(15), fm = wobble(3);
        double[] ph = new double[fr.length];
        for (int i = 0; i < ring.length; i++) {
            double t = i / (double) SR, v = 0;
            for (int p = 0; p < fr.length; p++) { ph[p] += 2 * Math.PI * fr[p] * (1 + 0.004 * fm.at(t)) / SR; v += am[p] * Math.sin(ph[p]); }
            ring[i] = v * (env(t, 0.006, 0.45) * 0.7 + 0.3 * env(t - 0.04, 0.12, 0.3)) * (0.7 + 0.3 * tr.at(t));
        }
        fadeOut(ring, 0.1);
        mix(s, norm(ring, 1), 0, 0.3);
        mix(s, clang(1450, 0.8, 0.3, 0.6), 0.002, 0.12);              // glassy shimmer
        return reverb(s, 1.2, 0.8, 0.35, 0.2, 0.35, 0.015);
    }

    // ================================================================ output
    static final List<String> REPORT = new ArrayList<>();
    static File wavDir, oggDir, ffmpeg;

    static void finish(String name, double[] x, double minLen, double maxLen, boolean trim) throws Exception {
        double[] y = hp(hp(x, 25), 25);
        double mean = 0;
        for (double v : y) mean += v;
        mean /= y.length;
        for (int i = 0; i < y.length; i++) y[i] -= mean;
        double pk = peak(y);
        int a = 0, b = y.length;
        if (trim) {
            double th = pk * db(-60);
            while (a < b && Math.abs(y[a]) < th) a++;
            a = Math.max(0, a - ns(0.001));
            double th2 = pk * db(-54);
            int e = b - 1;
            while (e > a && Math.abs(y[e]) < th2) e--;
            b = Math.min(y.length, e + ns(0.02));
        }
        boolean cut = false;
        if (b - a < ns(minLen)) b = Math.min(y.length, a + ns(minLen));
        double cutDb = 0;
        if (trim && b - a > ns(maxLen - 0.015)) {
            b = a + ns(maxLen - 0.015); cut = true;
            double sq = 0; int w = ns(Math.min(0.25, 0.3 * maxLen));
            for (int i = b - w; i < b; i++) sq = Math.max(sq, Math.abs(y[i]));
            cutDb = 20 * Math.log10(sq / pk);
        }
        double[] z = Arrays.copyOfRange(y, a, b);
        fadeIn(z, trim ? 0.003 : 0.002);
        fadeOut(z, cut ? Math.min(0.25, 0.3 * maxLen) : (trim ? 0.03 : 0.002));
        // 15 ms of digital silence after the fade: Vorbis spreads quantisation noise over its last block,
        // which otherwise leaves a -35 dB step at the very end of short files
        if (trim) z = Arrays.copyOf(z, Math.min(z.length + ns(0.015), ns(maxLen)));
        norm(z, TARGET);
        File wav = new File(wavDir, name + ".wav");
        writeWav(z, wav);
        String peakInfo = String.format("%.2f dBFS (wav)", 20 * Math.log10(peak(z)));
        if (ffmpeg != null) {
            File ogg = new File(oggDir, name + ".ogg");
            double p = 0;
            // Vorbis moves peaks a little: re-scale until the DECODED peak sits in [-1.15, -0.98] dBFS
            // (peaks of transients do not scale linearly through the codec, so keep the best try that stays <= -1 dBFS)
            double[] z0 = z.clone();
            double gain = 1, bestGain = -1, bestP = 0, lowGain = 1, lowP = 9;
            for (int it = 0; it < 10; it++) {
                for (int i = 0; i < z.length; i++) z[i] = z0[i] * gain;
                writeWav(z, wav);
                run(ffmpeg.getPath(), "-y", "-v", "error", "-i", wav.getPath(), "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5", ogg.getPath());
                p = decodedPeak(ogg);
                if (p <= TARGET * db(0.02) && p > bestP) { bestP = p; bestGain = gain; }
                if (p < lowP) { lowP = p; lowGain = gain; }
                if (p <= TARGET * db(0.02) && p >= TARGET * db(-0.15)) break;
                gain *= TARGET / p * 0.997;
                gain = Math.min(gain, 0.999 / peak(z0));
            }
            if (!(p <= TARGET * db(0.02) && p >= TARGET * db(-0.15))) {
                gain = bestGain > 0 ? bestGain : lowGain * 0.97;
                for (int i = 0; i < z.length; i++) z[i] = z0[i] * gain;
                writeWav(z, wav);
                run(ffmpeg.getPath(), "-y", "-v", "error", "-i", wav.getPath(), "-ac", "1", "-ar", "44100", "-c:a", "libvorbis", "-q:a", "5", ogg.getPath());
                p = decodedPeak(ogg);
            }
            peakInfo = String.format("%.2f dBFS (ogg decoded)", 20 * Math.log10(p));
        }
        String line = String.format("%-20s %6.3f s  %s  bands %s%s", name, z.length / (double) SR, peakInfo, bands(z),
                cut ? String.format("  [tail faded from %.0f dB]", cutDb) : "");
        REPORT.add(line);
        System.out.println(line);
    }

    /** energy share per band (dB relative to the whole file): <60 | 60-250 | 250-1k | 1k-4k | >4k */
    static String bands(double[] z) {
        double tot = rms(z);
        double[][] b = {
            lp(lp(z, 60), 60), lp(lp(hp(hp(z, 60), 60), 250), 250), lp(lp(hp(hp(z, 250), 250), 1000), 1000),
            lp(lp(hp(hp(z, 1000), 1000), 4000), 4000), hp(hp(z, 4000), 4000)
        };
        StringBuilder sb = new StringBuilder();
        for (double[] x : b) sb.append(String.format("%4.0f", 20 * Math.log10(Math.max(1e-9, rms(x)) / tot)));
        return sb.toString();
    }

    static void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (p.waitFor() != 0) throw new RuntimeException("ffmpeg failed: " + out);
    }

    static double decodedPeak(File ogg) throws Exception {
        File raw = new File(wavDir, "_decode.raw");
        run(ffmpeg.getPath(), "-y", "-v", "error", "-i", ogg.getPath(), "-f", "f32le", "-ac", "1", raw.getPath());
        ByteBuffer bb = ByteBuffer.wrap(Files.readAllBytes(raw.toPath())).order(ByteOrder.LITTLE_ENDIAN);
        double m = 0;
        while (bb.remaining() >= 4) m = Math.max(m, Math.abs(bb.getFloat()));
        raw.delete();
        return m;
    }

    static void writeWav(double[] x, File f) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (double v : x) {
            int s = (int) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
            pcm.write(s & 255);
            pcm.write((s >> 8) & 255);
        }
        byte[] data = pcm.toByteArray();
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + data.length)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(SR)); o.writeInt(Integer.reverseBytes(SR * 2)); o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(data.length)); o.write(data);
        }
    }

    static List<String> only;

    static void gen(String name, double min, double max, Supplier<double[]> f) throws Exception { gen(name, min, max, true, f); }

    static void gen(String name, double min, double max, boolean trim, Supplier<double[]> f) throws Exception {
        if (only != null && !only.contains(name)) return;
        rnd = new Random(0x5EEDL ^ ((long) name.hashCode() * 2654435761L));
        finish(name, f.get(), min, max, trim);
    }

    public static void main(String[] args) throws Exception {
        wavDir = new File(args.length > 0 ? args[0] : ".");
        wavDir.mkdirs();
        if (args.length > 2) { ffmpeg = new File(args[1]); oggDir = new File(args[2]); oggDir.mkdirs(); }
        if (args.length > 3) only = Arrays.asList(args[3].split(","));

        gen("minotaur_roar1", 2.5, 3.5, () -> minotaurRoar(0));
        gen("minotaur_roar2", 2.5, 3.5, () -> minotaurRoar(1));
        gen("minotaur_snort1", 0.6, 0.9, () -> minotaurSnort(0));
        gen("minotaur_snort2", 0.6, 0.9, () -> minotaurSnort(1));
        for (int i = 0; i < 3; i++) { final int v = i; gen("minotaur_grunt" + (i + 1), 0.3, 0.6, () -> minotaurGrunt(v)); }
        gen("minotaur_hurt1", 0.5, 0.8, () -> minotaurHurt(0));
        gen("minotaur_hurt2", 0.5, 0.8, () -> minotaurHurt(1));
        gen("minotaur_death", 4.0, 5.0, GenBossSounds::minotaurDeath);
        for (int i = 0; i < 3; i++) { final int v = i; gen("minotaur_step" + (i + 1), 0.3, 0.5, () -> minotaurStep(v)); }
        for (int i = 0; i < 3; i++) { final int v = i; gen("axe_swing" + (i + 1), 0.5, 0.8, () -> axeSwing(v)); }
        gen("axe_impact1", 1.2, 1.8, () -> axeImpact(0));
        gen("axe_impact2", 1.2, 1.8, () -> axeImpact(1));
        gen("axe_spin", 1.2, 1.6, false, GenBossSounds::axeSpin);
        gen("charge", 1.8, 2.2, GenBossSounds::charge);
        gen("wall_rise", 1.8, 2.4, GenBossSounds::wallRise);
        for (int i = 0; i < 6; i++) { final int v = i; gen("voice_minotaur" + (i + 1), 0.1, 0.2, () -> voiceMinotaur(v)); }
        gen("cerberus_growl1", 1.2, 1.8, () -> cerberusGrowl(0));
        gen("cerberus_growl2", 1.2, 1.8, () -> cerberusGrowl(1));
        for (int i = 0; i < 3; i++) { final int v = i; gen("cerberus_bark" + (i + 1), 0.3, 0.45, () -> cerberusBark(v)); }
        gen("cerberus_bite1", 0.3, 0.5, () -> cerberusBite(0));
        gen("cerberus_bite2", 0.3, 0.5, () -> cerberusBite(1));
        gen("cerberus_howl", 2.8, 3.5, GenBossSounds::cerberusHowl);
        gen("cerberus_flame", 2.2, 2.8, GenBossSounds::cerberusFlame);
        gen("cerberus_hurt1", 0.4, 0.6, () -> cerberusHurt(0));
        gen("cerberus_hurt2", 0.4, 0.6, () -> cerberusHurt(1));
        gen("cerberus_whimper", 1.2, 1.8, GenBossSounds::cerberusWhimper);
        gen("cerberus_death", 2.5, 3.5, GenBossSounds::cerberusDeath);
        for (int i = 0; i < 3; i++) { final int v = i; gen("cerberus_step" + (i + 1), 0.25, 0.4, () -> cerberusStep(v)); }
        gen("gate_open", 3.0, 4.0, GenBossSounds::gateOpen);
        gen("chains1", 0.8, 1.2, () -> chainsSound(0));
        gen("chains2", 0.8, 1.2, () -> chainsSound(1));
        gen("fire_burst", 1.0, 1.4, GenBossSounds::fireBurst);
        gen("geyser", 1.2, 1.8, GenBossSounds::geyser);
        gen("impact_heavy", 1.8, 2.4, GenBossSounds::impactHeavy);
        gen("stinger", 2.5, 3.5, GenBossSounds::stinger);
        gen("heartbeat", 1.0, 1.3, GenBossSounds::heartbeat);
        gen("paralyze", 0.8, 1.2, GenBossSounds::paralyze);
        System.out.println("done: " + REPORT.size() + " sounds");
    }
}
