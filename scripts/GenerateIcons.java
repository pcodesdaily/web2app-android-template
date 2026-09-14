import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import javax.imageio.ImageIO;

/**
 * Generates the launcher icon set from a single source image.
 *
 * Run as a single source file - "java GenerateIcons.java ..." - so it needs no
 * build step and no dependency. That is the whole reason it is Java rather than
 * a shell script: the runner already installs JDK 17 for the Android build, and
 * javax.imageio plus Java2D can decode, scale and composite without installing
 * anything. ImageMagick is NOT present on GitHub's Ubuntu runners, so the
 * obvious alternative would mean an apt-get on every build, paid for out of the
 * customer's own Actions minutes.
 *
 * It writes two different things, because Android needs two:
 *
 *   - An adaptive icon (API 26+). The launcher masks it to whatever shape it
 *     likes, so anything outside the central 66% can be cropped away.
 *
 *   - Legacy bitmaps for API 24 and 25, which are NOT masked. The template
 *     previously shipped only the adaptive icon, so on those two API levels -
 *     which minSdk 24 explicitly supports - the launcher icon did not resolve
 *     at all. These fix that.
 *
 * Usage:
 *   java GenerateIcons.java [source-image] [res-dir] [padding-percent] [#rrggbb]
 */
public class GenerateIcons {

    /** Launcher icon edge in pixels per density bucket (48dp baseline). */
    private static final int[] DENSITY_SIZES = { 48, 72, 96, 144, 192 };
    private static final String[] DENSITY_DIRS = {
        "mipmap-mdpi", "mipmap-hdpi", "mipmap-xhdpi", "mipmap-xxhdpi", "mipmap-xxxhdpi",
    };

    /**
     * Adaptive icons are 108dp square with the inner 72dp guaranteed visible.
     * 432px is 108dp at xxxhdpi, the largest any launcher asks for.
     */
    private static final int ADAPTIVE_SIZE = 432;
    private static final double SAFE_ZONE_RATIO = 72.0 / 108.0;

    /** Below this the highest-density bitmap has to be upscaled and goes soft. */
    private static final int MIN_USEFUL_EDGE = 192;

    public static void main(String[] args) throws IOException {
        if (args.length < 4) {
            System.err.println("usage: GenerateIcons <source-image> <res-dir> <padding-percent> <#rrggbb>");
            System.exit(2);
        }

        File source = new File(args[0]);
        File resDir = new File(args[1]);
        int padding = clamp(Integer.parseInt(args[2]), 0, 25);
        Color background = parseHex(args[3]);

        BufferedImage original = ImageIO.read(source);
        if (original == null) {
            // ImageIO returns null rather than throwing for a format it cannot
            // read, which is the easiest way to get a confusing NPE instead of
            // a useful message.
            System.err.println("::error::Could not decode the icon. Expected a PNG or JPEG image.");
            System.exit(1);
            return;
        }
        System.out.println("source: " + original.getWidth() + "x" + original.getHeight());

        /*
         * A /favicon.ico is usually 32px. Upscaled to 192 it is a blurred mess,
         * and the user deserves to learn that from the build log rather than
         * from the home screen of their own phone.
         */
        int shortestEdge = Math.min(original.getWidth(), original.getHeight());
        if (shortestEdge < MIN_USEFUL_EDGE) {
            System.out.println("::warning::Icon source is only " + shortestEdge
                + "px. Android uses 192px at the highest density, so this will look soft."
                + " A larger apple-touch-icon, or an uploaded image, would be sharper.");
        }

        /*
         * Whether the source is a finished icon or artwork needing a background.
         *
         * A touch icon is normally drawn edge to edge and is complete in itself;
         * placing it on a coloured card produces a square inside a square. A
         * logo with transparent margins is the opposite: drawn on nothing it has
         * no icon shape at all. Measuring the border tells the two apart, which
         * is more reliable than asking the user a question they would have to
         * open the file to answer.
         */
        boolean fullBleed = hasOpaqueBorder(original);
        System.out.println("treating source as " + (fullBleed ? "a finished icon" : "artwork on a background"));

        writeAdaptiveForeground(original, resDir, fullBleed ? 0 : padding);
        writeLegacyBitmaps(original, resDir, padding, background, fullBleed);
        writeAdaptiveXml(resDir, fullBleed);

        /*
         * The shipped placeholder is a vector with the same resource name. Two
         * resources sharing a name across folders is a build failure, so the
         * vector has to go once a real icon exists.
         */
        File vector = new File(resDir, "drawable/ic_launcher_foreground.xml");
        if (vector.exists() && !vector.delete()) {
            System.err.println("::error::Could not remove the placeholder " + vector);
            System.exit(1);
        }

        System.out.println("icons generated");
    }

    /**
     * The adaptive foreground: transparent, artwork inside the safe zone. The
     * background layer supplies the colour, so this must not fill it.
     */
    private static void writeAdaptiveForeground(BufferedImage source, File resDir, int padding)
            throws IOException {
        int safe = (int) Math.round(ADAPTIVE_SIZE * SAFE_ZONE_RATIO);
        int artwork = (int) Math.round(safe * (1.0 - padding / 100.0));

        BufferedImage canvas = new BufferedImage(ADAPTIVE_SIZE, ADAPTIVE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        applyQuality(g);
        BufferedImage scaled = scaleToFit(source, artwork);
        g.drawImage(scaled, (ADAPTIVE_SIZE - scaled.getWidth()) / 2, (ADAPTIVE_SIZE - scaled.getHeight()) / 2, null);
        g.dispose();

        write(canvas, new File(resDir, "drawable/ic_launcher_foreground.png"));
    }

    /**
     * Legacy bitmaps for API 24 and 25, which the launcher draws unmasked.
     *
     * A full-bleed source fills the frame and is rounded; artwork with
     * transparency sits on a coloured card, because a transparent launcher icon
     * on those versions looks broken rather than minimal.
     */
    private static void writeLegacyBitmaps(
            BufferedImage source, File resDir, int padding, Color background, boolean fullBleed)
            throws IOException {
        for (int i = 0; i < DENSITY_SIZES.length; i++) {
            int size = DENSITY_SIZES[i];

            BufferedImage canvas = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            applyQuality(g);

            // ~22% corner radius is the pre-adaptive convention; it reads as an
            // app icon rather than a sticker.
            RoundRectangle2D shape = new RoundRectangle2D.Double(0, 0, size, size, size * 0.44, size * 0.44);

            if (fullBleed) {
                g.setClip(shape);
                BufferedImage scaled = scaleToFill(source, size);
                g.drawImage(scaled, (size - scaled.getWidth()) / 2, (size - scaled.getHeight()) / 2, null);
            } else {
                g.setColor(background);
                g.fill(shape);
                int artwork = (int) Math.round(size * (1.0 - padding / 100.0) * 0.82);
                BufferedImage scaled = scaleToFit(source, artwork);
                g.drawImage(scaled, (size - scaled.getWidth()) / 2, (size - scaled.getHeight()) / 2, null);
            }
            g.dispose();

            write(canvas, new File(resDir, DENSITY_DIRS[i] + "/ic_launcher.png"));
        }
    }

    /**
     * The adaptive icon definition.
     *
     * Generated rather than shipped, because the monochrome layer is only
     * correct in one of the two cases. Android 13's themed icons tint the
     * layer's alpha: artwork with transparency tints into a recognisable
     * silhouette, while a full-bleed opaque icon tints into a solid blob.
     * Declaring it unconditionally would give half of all generated apps a
     * featureless shape on themed home screens.
     */
    private static void writeAdaptiveXml(File resDir, boolean fullBleed) throws IOException {
        String monochrome = fullBleed
            ? "    <!-- Omitted: this icon is opaque edge to edge, so a themed\n"
                + "         launcher would tint it into a featureless shape. -->\n"
            : "    <monochrome android:drawable=\"@drawable/ic_launcher_foreground\" />\n";

        String xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<!-- Generated by scripts/GenerateIcons.java. Edits are overwritten. -->\n"
            + "<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n"
            + "    <background android:drawable=\"@color/ic_launcher_background\" />\n"
            + "    <foreground android:drawable=\"@drawable/ic_launcher_foreground\" />\n"
            + monochrome
            + "</adaptive-icon>\n";

        File target = new File(resDir, "mipmap-anydpi-v26/ic_launcher.xml");
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IOException("could not create " + parent);
        Files.writeString(target.toPath(), xml);
        System.out.println("wrote " + target.getPath() + (fullBleed ? " (no monochrome layer)" : ""));
    }

    /** True when the outer ring of pixels is essentially opaque. */
    private static boolean hasOpaqueBorder(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int opaque = 0;
        int sampled = 0;

        for (int x = 0; x < w; x++) {
            for (int y : new int[] { 0, h - 1 }) {
                sampled++;
                if ((image.getRGB(x, y) >>> 24) > 200) opaque++;
            }
        }
        for (int y = 0; y < h; y++) {
            for (int x : new int[] { 0, w - 1 }) {
                sampled++;
                if ((image.getRGB(x, y) >>> 24) > 200) opaque++;
            }
        }
        return sampled > 0 && (double) opaque / sampled > 0.9;
    }

    /**
     * Scales preserving aspect ratio, halving repeatedly on the way down.
     *
     * A single bicubic step from 512px to 48px discards most of the source and
     * produces a soft, aliased result. Halving until within 2x and doing the
     * final step properly is the standard fix and costs nothing here.
     */
    private static BufferedImage scaleToFit(BufferedImage source, int maxEdge) {
        double scale = Math.min((double) maxEdge / source.getWidth(), (double) maxEdge / source.getHeight());
        return progressiveScale(source, scale);
    }

    /** Scales so the image covers a square of the given edge, cropping the rest. */
    private static BufferedImage scaleToFill(BufferedImage source, int edge) {
        double scale = Math.max((double) edge / source.getWidth(), (double) edge / source.getHeight());
        return progressiveScale(source, scale);
    }

    private static BufferedImage progressiveScale(BufferedImage source, double scale) {
        int targetW = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetH = Math.max(1, (int) Math.round(source.getHeight() * scale));

        BufferedImage current = source;
        int w = source.getWidth();
        int h = source.getHeight();

        while (w / 2 > targetW && h / 2 > targetH) {
            w = Math.max(targetW, w / 2);
            h = Math.max(targetH, h / 2);
            current = redraw(current, w, h);
        }
        return redraw(current, targetW, targetH);
    }

    private static BufferedImage redraw(BufferedImage source, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        applyQuality(g);
        g.setComposite(AlphaComposite.Src);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void applyQuality(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    }

    private static void write(BufferedImage image, File target) throws IOException {
        File parent = target.getParentFile();
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        if (!ImageIO.write(image, "png", target)) {
            throw new IOException("no PNG writer available");
        }
        System.out.println("wrote " + target.getPath() + " (" + image.getWidth() + "px)");
    }

    private static Color parseHex(String value) {
        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.length() != 6) {
            System.err.println("::error::Expected an icon background of the form #rrggbb, got " + value);
            System.exit(2);
        }
        return new Color(Integer.parseInt(hex, 16));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
