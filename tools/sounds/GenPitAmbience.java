import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Random;

/**
 * Ambience of the Vein Hollows / the heart of the Slaughter Pit. Synthesised, no samples.
 *
 *   java tools/sounds/GenPitAmbience.java <wavDir> [<ffmpeg.exe> <oggDir>]
 *
 *  - pit_heart_beat     one "lub-dub" of the giant heart: deep chest thump + wet flesh, small cave tail (mono)
 *  - hollows_heartbeat  the same heard through rock: muffled, far, long tail (mono)
 *  - hollows_loop       20 s seamless ambient loop of the biome: deep breathing drone, rumble, faint drips (mono)
 */
public class GenPitAmbience {
    static final int SR = 44100;
    static Random rnd = new Random(7);

    public static void main(String[] args) throws Exception {
        File wav = new File(args[0]);
        wav.mkdirs();
        write(new File(wav, "pit_heart_beat.wav"), norm(beat(false), 0.89));
        write(new File(wav, "hollows_heartbeat.wav"), norm(beat(true), 0.8));
        write(new File(wav, "hollows_loop.wav"), norm(loop(), 0.5));
        if (args.length >= 3) {
            File ogg = new File(args[2]);
            ogg.mkdirs();
            for (String n : new String[]{"pit_heart_beat", "hollows_heartbeat", "hollows_loop"}) {
                Process p = new ProcessBuilder(args[1], "-y", "-loglevel", "error", "-i", new File(wav, n + ".wav").getPath(),
                    "-ac", "1", "-c:a", "libvorbis", "-q:a", "6", new File(ogg, n + ".ogg").getPath()).inheritIO().start();
                if (p.waitFor() != 0) throw new IllegalStateException("ffmpeg failed for " + n);
            }
        }
        System.out.println("done");
    }

    // ------------------------------------------------------------------ dsp
    static double[] lp(double[] x, double f) {
        double a = 1 - Math.exp(-2 * Math.PI * f / SR), y = 0;
        double[] o = new double[x.length];
        for (int i = 0; i < x.length; i++) { y += a * (x[i] - y); o[i] = y; }
        return o;
    }
    static double[] lp2(double[] x, double f) { return lp(lp(x, f), f); }
    static double[] hp(double[] x, double f) {
        double[] l = lp(x, f);
        double[] o = new double[x.length];
        for (int i = 0; i < x.length; i++) o[i] = x[i] - l[i];
        return o;
    }
    static double[] bp(double[] x, double lo, double hi) { return lp(hp(x, lo), hi); }
    static double[] norm(double[] x, double p) {
        double m = 0;
        for (double v : x) m = Math.max(m, Math.abs(v));
        if (m > 0) for (int i = 0; i < x.length; i++) x[i] *= p / m;
        return x;
    }
    /** Small mono room: a few damped combs + an allpass. */
    static double[] room(double[] x, double size, double damp, double wet) {
        int[] d = {(int) (1557 * size), (int) (1617 * size), (int) (1491 * size), (int) (1422 * size), (int) (1277 * size), (int) (1356 * size)};
        double[] o = new double[x.length];
        for (int dl : d) {
            double[] buf = new double[dl];
            int idx = 0;
            double f = 0;
            for (int i = 0; i < x.length; i++) {
                double out = buf[idx];
                f = out * (1 - damp) + f * damp;
                buf[idx] = x[i] + f * 0.84;
                idx = (idx + 1) % dl;
                o[i] += out / d.length;
            }
        }
        for (int i = 0; i < x.length; i++) o[i] = x[i] * (1 - wet) + o[i] * wet * 2.2;
        return o;
    }

    /** One heart sound: a deep falling sine thump + a short body knock + wet noise. */
    static void thump(double[] out, double at, double f0, double f1, double len, double gain) {
        int s = (int) (at * SR);
        double ph = 0;
        int n = (int) (len * SR);
        for (int i = 0; i < n && s + i < out.length; i++) {
            double t = i / (double) SR;
            double f = f1 + (f0 - f1) * Math.exp(-t * 18);
            ph += 2 * Math.PI * f / SR;
            double env = (1 - Math.exp(-t * 400)) * Math.exp(-t * 11);
            double v = Math.sin(ph) * env + 0.35 * Math.sin(ph * 2.02) * env * Math.exp(-t * 20);
            out[s + i] += v * gain;
        }
        // wet flesh: short band-limited noise burst
        double[] nz = new double[(int) (0.12 * SR)];
        for (int i = 0; i < nz.length; i++) nz[i] = (rnd.nextDouble() * 2 - 1) * Math.exp(-i / (0.025 * SR));
        nz = bp(nz, 180, 700);
        for (int i = 0; i < nz.length && s + i < out.length; i++) out[s + i] += nz[i] * gain * 0.55;
    }

    static double[] beat(boolean far) {
        double[] x = new double[(int) ((far ? 2.4 : 1.5) * SR)];
        thump(x, 0.02, 95, 46, 0.5, 1.0);            // lub
        thump(x, 0.30, 115, 58, 0.4, 0.62);          // dub
        double[] y = far ? lp2(x, 160) : lp(x, 1400);
        y = room(y, far ? 1.6 : 1.0, far ? 0.6 : 0.4, far ? 0.55 : 0.22);
        // fade the tail
        int n = y.length;
        for (int i = 0; i < n; i++) { double t = i / (double) n; if (t > 0.6) y[i] *= Math.cos((t - 0.6) / 0.4 * Math.PI / 2); }
        return y;
    }

    /** 20 s loop: everything is periodic in the loop length, so the end joins the start. */
    static double[] loop() {
        double L = 20.0;
        int n = (int) (L * SR);
        double[] x = new double[n];
        // brown rumble: generate 2 loops long and crossfade the halves -> seamless
        double[] br = new double[2 * n];
        double b = 0;
        for (int i = 0; i < br.length; i++) { b = (b + (rnd.nextDouble() * 2 - 1) * 0.02) * 0.998; br[i] = b; }
        br = lp2(br, 110);
        for (int i = 0; i < n; i++) {
            double t = i / (double) n;
            x[i] = br[i] * t + br[i + n] * (1 - t);      // end runs into br[n] = start: seamless
        }
        norm(x, 0.5);
        for (int i = 0; i < n; i++) {
            double t = i / (double) SR;
            // breathing: two slow swells per loop
            double breath = 0.55 + 0.45 * Math.sin(2 * Math.PI * t * 2 / L - 1.2);
            double hum = Math.sin(2 * Math.PI * 41 * t) * 0.18 + Math.sin(2 * Math.PI * 61.5 * t + 1) * 0.08 * (0.5 + 0.5 * Math.sin(2 * Math.PI * t * 3 / L));
            x[i] = x[i] * (0.6 + 0.4 * breath) + hum * breath;
        }
        // a few distant drips (placed so they never cross the loop point)
        double[] drips = {2.3, 7.9, 11.2, 16.6};
        for (double at : drips) {
            int s = (int) (at * SR);
            double f = 900 + rnd.nextDouble() * 500;
            for (int i = 0; i < (int) (0.25 * SR); i++) {
                double t = i / (double) SR;
                double fr = f * (1 + 0.6 * Math.exp(-t * 60));
                x[s + i] += Math.sin(2 * Math.PI * fr * t) * Math.exp(-t * 28) * 0.05;
            }
        }
        // reverb over two loops, keep the second (its tail wraps around like in the game)
        double[] two = new double[2 * n];
        for (int i = 0; i < 2 * n; i++) two[i] = x[i % n];
        two = room(two, 1.8, 0.5, 0.25);
        double[] out = new double[n];
        System.arraycopy(two, n, out, 0, n);
        return out;
    }

    static void write(File f, double[] x) throws Exception {
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        for (double v : x) {
            int s = (int) Math.round(Math.max(-1, Math.min(1, v)) * 32767);
            pcm.write(s & 255); pcm.write((s >> 8) & 255);
        }
        byte[] d = pcm.toByteArray();
        try (DataOutputStream o = new DataOutputStream(new FileOutputStream(f))) {
            o.writeBytes("RIFF"); o.writeInt(Integer.reverseBytes(36 + d.length)); o.writeBytes("WAVE");
            o.writeBytes("fmt "); o.writeInt(Integer.reverseBytes(16)); o.writeShort(Short.reverseBytes((short) 1)); o.writeShort(Short.reverseBytes((short) 1));
            o.writeInt(Integer.reverseBytes(SR)); o.writeInt(Integer.reverseBytes(SR * 2)); o.writeShort(Short.reverseBytes((short) 2)); o.writeShort(Short.reverseBytes((short) 16));
            o.writeBytes("data"); o.writeInt(Integer.reverseBytes(d.length)); o.write(d);
        }
    }
}
