package com.cbruegg.emuserver.platform.ds;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.plugins.jpeg.JPEGImageWriteParam;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;

public class JpegCompressor {

    private static final int SINGLE_SCREEN_WIDTH = 256;
    private static final int SINGLE_SCREEN_HEIGHT = 192;
    private static final int SINGLE_SCREEN_SIZE_BYTES = SINGLE_SCREEN_WIDTH * SINGLE_SCREEN_HEIGHT * 4;

    private JpegCompressor() {
    }

    public static void compress(InputStream from, OutputStream into) throws IOException {
        var jpegParams = new JPEGImageWriteParam(null);
        jpegParams.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        jpegParams.setCompressionQuality(0.95f);
        var jpegWriter = ImageIO.getImageWritersByFormatName("jpg").next();
        try (var jpegOutputBuffer = new ByteArrayOutputStream(SINGLE_SCREEN_SIZE_BYTES * 2);
             var imageOutputStream = ImageIO.createImageOutputStream(jpegOutputBuffer);
             var fromChannel = Channels.newChannel(from)) {
            if (imageOutputStream == null) {
                throw new IOException("Can't create an ImageOutputStream!");
            }
            jpegWriter.setOutput(imageOutputStream);

            var imageBuffer = ByteBuffer.allocateDirect(SINGLE_SCREEN_SIZE_BYTES * 2).order(ByteOrder.LITTLE_ENDIAN);
            var imageIntBuffer = imageBuffer.asIntBuffer();
            var imageIntArray = new int[imageIntBuffer.remaining()];
            // TYPE_INT_RGB ust ignores alpha
            var bufferedImage = new BufferedImage(SINGLE_SCREEN_WIDTH, SINGLE_SCREEN_HEIGHT * 2, BufferedImage.TYPE_INT_BGR);
            var iioImage = new IIOImage(bufferedImage, null, null);

            var sizeBuf = new byte[4];
            while (fromChannel.read(imageBuffer) >= 0) {
                if (!imageBuffer.hasRemaining()) {
                    imageIntBuffer.get(imageIntArray);
                    bufferedImage.setRGB(0, 0, SINGLE_SCREEN_WIDTH, SINGLE_SCREEN_HEIGHT * 2, imageIntArray, 0, SINGLE_SCREEN_WIDTH);
                    jpegWriter.write(null, iioImage, jpegParams);

                    var size = jpegOutputBuffer.size();
                    sizeBuf[0] = (byte) (size >> 24);
                    sizeBuf[1] = (byte) (size >> 16);
                    sizeBuf[2] = (byte) (size >> 8);
                    sizeBuf[3] = (byte) (size);
                    into.write(sizeBuf);
                    into.flush();
                    jpegOutputBuffer.writeTo(into);
                    into.flush();

                    jpegOutputBuffer.reset();
                    imageIntBuffer.clear();
                    imageBuffer.clear();
                }
            }
        } finally {
            jpegWriter.dispose();
        }
    }

}
