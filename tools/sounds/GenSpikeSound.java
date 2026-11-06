import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Procedural sound design for the Nihil blood thorn (no samples, everything synthesised):
 *   spike_rumble   ground trembling and cracking before the thorn breaks out (~0.9 s)
 *   spike_stab1..3 one jerk of the thorn: deep thump, crunching earth, a glassy "blood crystal" ring,
 *                  a wet tail - each stab a bit higher and harder
 *   spike_impale   the final stab into the body: heavy boom, crystal shatter, gore squelch, sub drop (~1.4 s)
 *
 * Writes 16-bit mono 44.1 kHz WAV files into the given folder; convert them to OGG Vorbis for Minecraft:
 *   java tools/sounds/GenSpikeSound.java <wavDir>
 *   ffmpeg -i x.wav -c:a libvorbis -q:a 6 x.ogg
 */
public class GenSpikeSound {
    static final int SR = 44100;
    static Random rnd = new Random(1337);

    // ---------------------------------------------------------------- tiny DSP toolbox
    static double[] buf(double seconds) { return new double[(int) (seconds * SR)]; }

    /** one-pole low-pass in place */
    static void lowpass(double[] x, double cutoff) {
        double a = Math.exp(-2 * Math.PI * cutoff / SR), y = 0;
        for (int i = 0; i < x.length; i++) { y = (1 - a) * x[i] + a * y; x[i] = y; }
    }

    static void highpass(double[] x, double cutoff) {
        double[] lp = x.clone();
        lowpass(lp, cutoff);
        for (int i = 0; i < x.length; i++) x[i] -= lp[i];
    }

    /** state-variable band-pass with a time-varying centre frequency */
    static double[] bandpass(double[] x, java.util.function.IntToDoubleFunction freq, double q) {
        double[] out = new double[x.length];
        double low = 0, band = 0;
        for (int i = 0; i < x.length; i++) {
            double f = 2 * Math.sin(Math.PI * Math.min(freq.applyAsDouble(i), SR / 6.0) / SR);
            low += f * band;
            double high = x[i] - low - band / q;
            band += f * high;
            out[i] = band;
        }
        return out;
    }

    static double[] noise(int n) { double[] r = new double[n]; for (int i = 0; i < n; i++) r[i] = rnd.nextDouble() * 2 - 1; return r; }

    /** brown noise: integrated white noise, kept centred */
    static double[] brown(int n) {
        double[] r = new double[n];
        double v = 0;
        for (int i = 0; i < n; i++) { v = v * 0.997 + (rnd.nextDouble() * 2 - 1) * 0.08; r[i] = v; }
        return r;
    }

    static void mix(double[] dst, double[] src, int at, double gain) {
        for (int i = 0; i < src.length && at + i < dst.length; i++) if (at + i >= 0) dst[at + i] += src[i] * gain;
    }

    static double env(double t, double attack, double decay) {
        if (t < 0) return 0;
        return (t < attack ? t / attack : 1) * Math.exp(-(t - Math.min(t, attack)) / decay);
    }

    // ---------------------------------------------------------------- building blocks
    /** deep pitched thump: sine sweeping down, with a click transient */
    static double[] thump(double f0, double f1, double len, double decay) {
        double[] r = buf(len);
        double ph = 0;
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            double f = f1 + (f0 - f1) * Math.exp(-t * 18);
            ph += 2 * Math.PI * f / SR;
            r[i] = Math.sin(ph) * env(t, 0.003, decay);
            if (i < SR * 0.006) r[i] += (rnd.nextDouble() * 2 - 1) * (1 - i / (SR * 0.006)) * 0.8;
        }
        return r;
    }

    /** crunching earth / cracking: dense random clicks through a band-pass, decaying */
    static double[] crunch(double len, double density, double centre, double decay) {
        double[] r = buf(len);
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            if (rnd.nextDouble() < density * Math.exp(-t / decay) / SR * 1000) {
                double amp = 0.3 + rnd.nextDouble() * 0.7;
                int w = 20 + rnd.nextInt(120);
                for (int k = 0; k < w && i + k < r.length; k++) r[i + k] += (rnd.nextDouble() * 2 - 1) * amp * (1 - k / (double) w);
            }
        }
        double[] b = bandpass(r, i -> centre * (1 + 0.3 * Math.sin(i * 0.0007)), 1.2);
        for (int i = 0; i < b.length; i++) b[i] *= env(i / (double) SR, 0.002, decay);
        return b;
    }

    /** glassy "blood crystal" ring: inharmonic partials, slight detune, fast-ish decay */
    static double[] crystal(double base, double len, double decay) {
        double[] ratios = {1.0, 2.71, 4.13, 5.92, 8.34};
        double[] gains = {1.0, 0.6, 0.45, 0.3, 0.18};
        double[] r = buf(len);
        for (int p = 0; p < ratios.length; p++) {
            double f = base * ratios[p] * (1 + (rnd.nextDouble() - 0.5) * 0.01);
            double d = decay / (1 + p * 0.6);
            for (int i = 0; i < r.length; i++) {
                double t = i / (double) SR;
                r[i] += Math.sin(2 * Math.PI * f * t + p) * gains[p] * env(t, 0.001, d);
            }
        }
        return r;
    }

    /** wet, squelchy tail: noise through a wobbling resonant band-pass + little bubble chirps */
    static double[] squelch(double len, double decay, double wobble) {
        double[] n = noise((int) (len * SR));
        double[] r = bandpass(n, i -> 380 + 260 * Math.sin(i / (double) SR * wobble * 2 * Math.PI) + 180 * Math.sin(i / (double) SR * 31), 3.5);
        for (int i = 0; i < r.length; i++) r[i] *= env(i / (double) SR, 0.01, decay) * 1.6;
        for (int b = 0; b < 7; b++) {
            int at = (int) (rnd.nextDouble() * len * 0.7 * SR);
            double f0 = 250 + rnd.nextDouble() * 250, len2 = 0.02 + rnd.nextDouble() * 0.04;
            double ph = 0;
            for (int i = 0; i < len2 * SR && at + i < r.length; i++) {
                double t = i / (double) SR;
                ph += 2 * Math.PI * (f0 + t / len2 * f0 * 1.8) / SR;
                r[at + i] += Math.sin(ph) * (1 - t / len2) * 0.25 * Math.exp(-at / (double) SR / decay);
            }
        }
        return r;
    }

    /** low rumble: brown noise, low-passed, with a slow tremble */
    static double[] rumble(double len, double cutoff) {
        double[] r = brown((int) (len * SR));
        lowpass(r, cutoff);
        lowpass(r, cutoff);
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            double grow = Math.min(1, t / (len * 0.6));
            r[i] *= (0.6 + 0.4 * Math.sin(t * 2 * Math.PI * 11)) * grow * Math.min(1, (len - t) / 0.15) * 6;
        }
        return r;
    }

    /** cheap room: a few feedback delays */
    static double[] room(double[] x, double wet, double tail) {
        double[] r = new double[x.length + (int) (tail * SR)];
        System.arraycopy(x, 0, r, 0, x.length);
        int[] d = {(int) (0.029 * SR), (int) (0.037 * SR), (int) (0.053 * SR), (int) (0.071 * SR)};
        double[] out = r.clone();
        for (int dl : d) {
            double[] line = new double[r.length];
            for (int i = 0; i < r.length; i++) line[i] = r[i] + (i >= dl ? line[i - dl] * 0.55 : 0);
            lowpass(line, 3200);
            for (int i = 0; i < r.length; i++) out[i] += line[i] * wet / d.length;
        }
        return out;
    }

    static double[] normalize(double[] x, double peak) {
        double m = 1e-9;
        for (double v : x) m = Math.max(m, Math.abs(v));
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = Math.tanh(x[i] / m * 1.15) / Math.tanh(1.15) * peak;   // gentle saturation
        int fade = (int) (0.02 * SR);
        for (int i = 0; i < fade && i < r.length; i++) r[r.length - 1 - i] *= i / (double) fade;
        return r;
    }

    // ---------------------------------------------------------------- the sounds
    static double[] rumbleSound() {
        double[] s = buf(0.9);
        mix(s, rumble(0.9, 70), 0, 1.0);
        mix(s, crunch(0.9, 0.35, 900, 0.7), 0, 0.5);
        mix(s, crunch(0.9, 0.12, 2200, 0.5), (int) (0.2 * SR), 0.25);
        return normalize(room(s, 0.35, 0.3), 0.85);
    }

    static double[] stabSound(int stage) {
        double hard = 1 + stage * 0.35;
        double[] s = buf(0.75);
        mix(s, thump(140 + stage * 25, 42 + stage * 6, 0.45, 0.13), 0, 1.0 * hard);
        mix(s, crunch(0.5, 1.4 + stage * 0.6, 1400 + stage * 300, 0.12), 0, 0.75);
        double[] hp = noise((int) (0.05 * SR)); highpass(hp, 2500);
        for (int i = 0; i < hp.length; i++) hp[i] *= 1 - i / (double) hp.length;
        mix(s, hp, 0, 0.6);                                   // sharp tip breaking through
        mix(s, crystal(560 + stage * 90, 0.6, 0.18), (int) (0.005 * SR), 0.22 + stage * 0.05);
        mix(s, squelch(0.4, 0.12, 6), (int) (0.04 * SR), 0.25 + stage * 0.1);
        return normalize(room(s, 0.4, 0.35), 0.9);
    }

    static double[] impaleSound() {
        double[] s = buf(1.4);
        mix(s, thump(170, 34, 0.9, 0.3), 0, 1.3);
        mix(s, crunch(0.6, 3.0, 1800, 0.18), 0, 0.8);
        double[] hp = noise((int) (0.08 * SR)); highpass(hp, 3000);
        for (int i = 0; i < hp.length; i++) hp[i] *= 1 - i / (double) hp.length;
        mix(s, hp, 0, 0.8);
        mix(s, crystal(720, 1.0, 0.45), (int) (0.004 * SR), 0.4);  // the blood crystal rings out
        mix(s, crystal(480, 1.0, 0.6), (int) (0.012 * SR), 0.25);
        mix(s, squelch(1.0, 0.38, 4), (int) (0.05 * SR), 0.75);    // goring the body
        mix(s, thump(55, 28, 1.2, 0.5), (int) (0.06 * SR), 0.7);   // sub drop
        return normalize(room(s, 0.5, 0.6), 0.95);
    }

    // ---------------------------------------------------------------- bleeding
    /** fast swoosh: noise through a band-pass sweeping down */
    static double[] slash(double len, double fFrom, double fTo) {
        double[] n = noise((int) (len * SR));
        double[] r = bandpass(n, i -> fFrom + (fTo - fFrom) * Math.min(1, i / (len * SR)), 2.2);
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            r[i] *= Math.sin(Math.PI * Math.min(1, t / len)) * 1.8;
        }
        return r;
    }

    /** hissing spray of blood: high-passed noise with a fluttering envelope */
    static double[] spray(double len, double decay) {
        double[] n = noise((int) (len * SR));
        highpass(n, 1800);
        lowpass(n, 7000);
        for (int i = 0; i < n.length; i++) {
            double t = i / (double) SR;
            n[i] *= env(t, 0.004, decay) * (0.7 + 0.3 * Math.sin(t * 2 * Math.PI * 38));
        }
        return n;
    }

    /** heavy drop hitting the ground: short descending sine "plop" */
    static double[] plop(double f0) {
        double[] r = buf(0.09);
        double ph = 0;
        for (int i = 0; i < r.length; i++) {
            double t = i / (double) SR;
            ph += 2 * Math.PI * (f0 * Math.exp(-t * 18) + 180) / SR;
            r[i] = Math.sin(ph) * env(t, 0.001, 0.025);
        }
        return r;
    }

    /** deep, meaty squelch: low wobbling band-pass (v2 - Mats found the first bleed sounds too high/thin/hissy) */
    static double[] squelchLow(double len, double decay, double wobble, double base) {
        double[] n = noise((int) (len * SR));
        double[] r = bandpass(n, i -> base + base * 0.55 * Math.sin(i / (double) SR * wobble * 2 * Math.PI) + base * 0.3 * Math.sin(i / (double) SR * 23), 4.0);
        lowpass(r, 1400);
        for (int i = 0; i < r.length; i++) r[i] *= env(i / (double) SR, 0.012, decay) * 2.2;
        return r;
    }

    /** dull gush of blood: low-passed noise, no hiss */
    static double[] gush(double len, double decay) {
        double[] n = noise((int) (len * SR));
        lowpass(n, 700);
        lowpass(n, 700);
        highpass(n, 90);
        for (int i = 0; i < n.length; i++) n[i] *= env(i / (double) SR, 0.015, decay) * 3.0;
        return n;
    }

    static double[] bleedOpenSound() {
        double[] s = buf(0.8);
        mix(s, slash(0.12, 1500, 320), 0, 0.6);                    // a dull cut, no high whistle
        mix(s, thump(95, 38, 0.35, 0.09), (int) (0.02 * SR), 0.9); // meaty impact
        mix(s, gush(0.5, 0.16), (int) (0.04 * SR), 0.8);
        mix(s, squelchLow(0.55, 0.17, 6, 210), (int) (0.05 * SR), 1.0);
        mix(s, plop(480), (int) (0.38 * SR), 0.45);
        mix(s, plop(410), (int) (0.55 * SR), 0.3);
        double[] r = room(s, 0.22, 0.2);
        lowpass(r, 4500);
        return normalize(r, 0.85);
    }

    /** one pulse of the open wound: a very deep wet throb + a heavy drop (v3 - Mats: "noch tiefer") */
    static double[] bleedPulseSound(int variant) {
        double[] s = buf(0.55);
        double[] n = noise((int) (0.18 * SR));
        double[] squirt = bandpass(n, i -> 110 + 190 * Math.min(1, i / (0.14 * SR)), 3.5);
        lowpass(squirt, 600);
        for (int i = 0; i < squirt.length; i++) squirt[i] *= env(i / (double) SR, 0.012, 0.07) * 2.8;
        mix(s, squirt, 0, 0.8);
        mix(s, thump(58, 30, 0.28, 0.08), 0, 0.9);                // deep heartbeat push
        mix(s, squelchLow(0.3, 0.09, 7 + variant * 3, 115 + variant * 15), (int) (0.015 * SR), 0.8);
        mix(s, plop(300 + variant * 40), (int) ((0.24 + variant * 0.05) * SR), 0.5);
        double[] r = room(s, 0.18, 0.15);
        lowpass(r, 2500);
        return normalize(r, 0.85);
    }
    // ---------------------------------------------------------------- Blood Cyclone bolts (Mats: no "water splash")
    /** firing a blood bolt: sharp whistling "thwip" with a low punch and a hint of crystal - dry, not wet */
    static double[] boltFireSound(int variant) {
        double[] s = buf(0.4);
        mix(s, slash(0.16, 2600 + variant * 300, 650), 0, 0.9);
        mix(s, thump(130 + variant * 15, 55, 0.22, 0.05), 0, 0.8);
        double[] hp = noise((int) (0.02 * SR)); highpass(hp, 3500);
        for (int i = 0; i < hp.length; i++) hp[i] *= 1 - i / (double) hp.length;
        mix(s, hp, 0, 0.5);
        mix(s, crystal(900 + variant * 120, 0.3, 0.07), (int) (0.01 * SR), 0.18);
        double[] r = room(s, 0.2, 0.15);
        return normalize(r, 0.8);
    }

    /** a blood bolt hits: hard crack + shattering blood crystal + short thud - no splash */
    static double[] boltImpactSound(int variant) {
        double[] s = buf(0.45);
        double[] hp = noise((int) (0.03 * SR)); highpass(hp, 2500);
        for (int i = 0; i < hp.length; i++) hp[i] *= 1 - i / (double) hp.length;
        mix(s, hp, 0, 0.9);
        mix(s, crunch(0.3, 2.5, 2400 + variant * 300, 0.06), 0, 0.7);
        mix(s, crystal(1150 + variant * 140, 0.4, 0.1), (int) (0.003 * SR), 0.35);
        mix(s, crystal(1700 + variant * 90, 0.3, 0.07), (int) (0.012 * SR), 0.22);
        mix(s, thump(100, 45, 0.2, 0.05), 0, 0.6);
        double[] r = room(s, 0.25, 0.2);
        return normalize(r, 0.85);
    }

    static void writeWav(double[] x, File f) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (double v : x) {
            int s = (int) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
            pcm.write(s & 255); pcm.write((s >> 8) & 255);
        }
        byte[] data = pcm.toByteArray();
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + data.length)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(SR)); o.writeInt(Integer.reverseBytes(SR * 2)); o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(data.length)); o.write(data);
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        dir.mkdirs();
        writeWav(rumbleSound(), new File(dir, "spike_rumble.wav"));
        for (int st = 0; st < 3; st++) writeWav(stabSound(st), new File(dir, "spike_stab" + (st + 1) + ".wav"));
        writeWav(impaleSound(), new File(dir, "spike_impale.wav"));
        writeWav(bleedOpenSound(), new File(dir, "bleed_open.wav"));
        for (int v = 0; v < 2; v++) writeWav(bleedPulseSound(v), new File(dir, "bleed_pulse" + (v + 1) + ".wav"));
        for (int v = 0; v < 2; v++) {
            writeWav(boltFireSound(v), new File(dir, "bolt_fire" + (v + 1) + ".wav"));
            writeWav(boltImpactSound(v), new File(dir, "bolt_impact" + (v + 1) + ".wav"));
        }
        System.out.println("wrote 12 wav files to " + dir.getAbsolutePath());
    }
}
