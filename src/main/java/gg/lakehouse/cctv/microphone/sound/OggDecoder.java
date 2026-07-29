package gg.lakehouse.cctv.microphone.sound;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Ogg Vorbis to mono PCM via JOrbis; the server has no audio stack of its own. */
final class OggDecoder {
    /** Decoded mono samples at the file's own rate. */
    record Decoded(short[] samples, int sampleRate) {
    }

    private OggDecoder() {
    }

    @Nullable
    static Decoded decode(byte[] ogg) {
        try {
            return decodeOrThrow(ogg);
        } catch (Exception e) {
            return null;
        }
    }

    private static Decoded decodeOrThrow(byte[] ogg) throws IOException {
        var input = new ByteArrayInputStream(ogg);
        var syncState = new SyncState();
        var streamState = new StreamState();
        var page = new Page();
        var packet = new Packet();
        var info = new Info();
        var comment = new Comment();
        var dspState = new DspState();
        var block = new Block(dspState);

        syncState.init();
        var out = new java.io.ByteArrayOutputStream();
        int channels = 0;
        int rate = 0;
        boolean headersDone = false;
        int headersRead = 0;

        while (true) {
            int index = syncState.buffer(4096);
            int read = input.read(syncState.data, index, 4096);
            if (read > 0) syncState.wrote(read);
            if (syncState.pageout(page) != 1) {
                if (read <= 0) break;
                continue;
            }
            if (!headersDone && headersRead == 0) {
                streamState.init(page.serialno());
                info.init();
                comment.init();
            }
            if (streamState.pagein(page) < 0) throw new IOException("Corrupt Ogg page");
            while (streamState.packetout(packet) == 1) {
                if (!headersDone) {
                    if (info.synthesis_headerin(comment, packet) < 0) throw new IOException("Not Vorbis");
                    if (++headersRead == 3) {
                        headersDone = true;
                        channels = info.channels;
                        rate = info.rate;
                        dspState.synthesis_init(info);
                        block.init(dspState);
                    }
                    continue;
                }
                if (block.synthesis(packet) == 0) dspState.synthesis_blockin(block);
                var pcmChannels = new float[1][][];
                var channelIndex = new int[channels];
                int samples;
                while ((samples = dspState.synthesis_pcmout(pcmChannels, channelIndex)) > 0) {
                    for (int i = 0; i < samples; i++) {
                        float sum = 0;
                        for (int c = 0; c < channels; c++) {
                            sum += pcmChannels[0][c][channelIndex[c] + i];
                        }
                        int value = Math.round(sum / channels * 32767);
                        value = Math.max(-32768, Math.min(32767, value));
                        out.write(value & 0xFF);
                        out.write((value >> 8) & 0xFF);
                    }
                    dspState.synthesis_read(samples);
                }
            }
            if (page.eos() != 0) break;
        }
        if (!headersDone || rate <= 0) throw new IOException("No Vorbis stream");

        var bytes = out.toByteArray();
        var result = new short[bytes.length / 2];
        for (int i = 0; i < result.length; i++) {
            result[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
        }
        return new Decoded(result, rate);
    }
}
