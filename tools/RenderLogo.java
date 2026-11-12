import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the app logo: a blue rounded square with three white sliders (a control panel; three
 * bars for the panel slots). Writes the window icons and the Windows .ico.
 *
 * <pre>java tools/RenderLogo.java</pre>
 *
 * Same geometry as docs/logo.svg (256 x 256 design grid).
 */
public class RenderLogo {
    static final int[] SIZES = {16, 24, 32, 48, 64, 128, 256};

    static final Color BLUE_TOP = new Color(0x3B74F0);
    static final Color BLUE_BOTTOM = new Color(0x1E54CB);
    static final Color TRACK = new Color(255, 255, 255, 110);
    static final Color WHITE = Color.WHITE;
    static final Color DOT = new Color(0x1A47A8);

    /** y of each slider and x of its knob, on the 256 grid. */
    static final double[][] SLIDERS = {{84, 156}, {128, 96}, {172, 136}};
    /** 16 and 24 px: two sliders, otherwise the knobs run into each other. */
    static final double[][] SLIDERS_TINY = {{96, 152}, {160, 104}};

    static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        double s = size / 256.0;
        g.scale(s, s);

        // small sizes: thicker strokes and bigger knobs so the sliders stay readable
        boolean tiny = size <= 24;
        double track = tiny ? 22 : 14;
        double knob = tiny ? 32 : 23;
        double dot = tiny ? 0 : 8;
        double[][] sliders = tiny ? SLIDERS_TINY : SLIDERS;

        g.setPaint(new GradientPaint(0, 8, BLUE_TOP, 0, 248, BLUE_BOTTOM));
        g.fill(new RoundRectangle2D.Double(8, 8, 240, 240, 112, 112));

        for (double[] sl : sliders) {
            double y = sl[0];
            double x = sl[1];
            g.setStroke(new BasicStroke((float) track, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(TRACK);
            g.draw(new Line2D.Double(x, y, 196, y));
            g.setColor(WHITE);
            g.draw(new Line2D.Double(60, y, x, y));
            g.fill(new Ellipse2D.Double(x - knob, y - knob, knob * 2, knob * 2));
            if (dot > 0) {
                g.setColor(DOT);
                g.fill(new Ellipse2D.Double(x - dot, y - dot, dot * 2, dot * 2));
            }
        }
        g.dispose();
        return img;
    }

    /** Windows .ico with PNG-compressed entries (Vista and later). */
    static byte[] ico(List<byte[]> pngs, int[] sizes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer head = ByteBuffer.allocate(6 + 16 * pngs.size()).order(ByteOrder.LITTLE_ENDIAN);
        head.putShort((short) 0).putShort((short) 1).putShort((short) pngs.size());
        int offset = head.capacity();
        for (int i = 0; i < pngs.size(); i++) {
            int sz = sizes[i];
            head.put((byte) (sz >= 256 ? 0 : sz)).put((byte) (sz >= 256 ? 0 : sz))
                    .put((byte) 0).put((byte) 0)
                    .putShort((short) 1).putShort((short) 32)
                    .putInt(pngs.get(i).length).putInt(offset);
            offset += pngs.get(i).length;
        }
        out.write(head.array());
        for (byte[] p : pngs) {
            out.write(p);
        }
        return out.toByteArray();
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path icons = Files.createDirectories(root.resolve("src/main/resources/icons"));
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ImageIO.write(render(size), "png", buf);
            pngs.add(buf.toByteArray());
            Files.write(icons.resolve("panel-controller-" + size + ".png"), buf.toByteArray());
        }
        Files.write(root.resolve("docs/panel-controller.ico"), ico(pngs, SIZES));
        System.out.println("icons written to " + icons.toAbsolutePath());
    }
}
