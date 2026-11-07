import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Status effect icon "Paralysed" (textures/mob_effect/paralyzed.png, 18x18) - the Howler's / Minotaur's howl:
 * a violet shock bolt with a dark outline, a bright core and two small sparks.
 * Run: java tools/textures/GenParalyzedIcon.java .
 */
public class GenParalyzedIcon {
    static final String[] ART = {
        "..................",
        ".........oooo.....",
        "........oVVVo.....",
        ".......oVWVo......",
        "......oVWVo....o..",
        ".....oVWVo....oso.",
        "....oVWVVoooo..o..",
        "...oVWWWWVVVo.....",
        "...ooooVVWWVo.....",
        "......oVWVVo......",
        ".....oVWVVo.......",
        "....oVWVdo........",
        "...oVWVdo.........",
        "..oVVddo..........",
        ".oVddoo......o....",
        ".oddo.......oso...",
        ".ooo.........o....",
        "..................",
    };

    static int color(char c) {
        return switch (c) {
            case 'o' -> 0xFF14051F;   // outline
            case 'V' -> 0xFF8A40FF;   // violet (the howl's warning colour)
            case 'd' -> 0xFF4A1C8A;   // shadow side
            case 'W' -> 0xFFE6D2FF;   // bright core
            case 's' -> 0xFFC9A6FF;   // spark
            default -> 0;
        };
    }

    public static void main(String[] args) throws Exception {
        String root = args.length > 0 ? args[0] : ".";
        BufferedImage img = new BufferedImage(18, 18, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 18; y++) for (int x = 0; x < 18; x++) img.setRGB(x, y, color(ART[y].charAt(x)));
        File out = new File(root, "blood/src/main/resources/assets/hha_blood/textures/mob_effect/paralyzed.png");
        out.getParentFile().mkdirs();
        ImageIO.write(img, "png", out);
        System.out.println("wrote " + out);
    }
}
