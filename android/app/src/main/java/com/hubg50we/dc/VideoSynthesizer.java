package com.hubg50we.dc;

import android.graphics.Bitmap;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Universal H.264 Video Synthesizer & RFC 6184 RTP Packetizer for Discord Voice & Screen Sharing.
 * Eliminates Black Screen on Android Mobile by transmitting compliant H.264 Baseline streams.
 */
public class VideoSynthesizer {
    private static final String TAG = "VideoSynthesizer";
    public static final int MAX_RTP_PAYLOAD = 1200;

    // Compliant 320x240 H.264 Baseline Profile Sequence & Picture Parameter Sets
    private static final String SPS_B64 = "Z0LAHtkBQfsBagQECoAAAAMAgAAAAwEHixck";
    private static final String PPS_B64 = "aMuDyyA=";

    // Universal SMPTE Color Bars Broadcast Test Card IDR Keyframe (320x240 H.264 Baseline)
    private static final String SMPTE_IDR_B64 =
            "ZYiEBXyYoAAvvSf/D/gsBFA6CFQcQAAQBABYglgcAAsAKDLkOAAWAFBlyB0h3IdIdxoEZYAAgD2uPjD/QLATRQPEAAEAcAaI" +
            "AAICIAEwcAgCsQcAgCsQcACADsQcACADsHQJ6OAG1x+H/QLATAcAQCBqD4gAAgOAAHiAACBUAAIB+BwCGEXIcAhhFyBwAIEH" +
            "XIcACBB1w6BPRwAbXHgH/oEYKhQABAI8QAA+A0QAAQPgABAjtArtcQw//BWCqDgCAIpwgACIgmgGAAED0CAGgeMlzDxkuYHA" +
            "BhtzDgAgw25tArFAAEAa1xDD/6DwKooAAgvdoFYoAAgDWv//8FYIsUAA9iAACAgAAILKEACACoHAAQAQyDgAIAIZ4OgRkHQI" +
            "z2v/D/QKoQEpCgLgEZAnQKokx53q9+EynPXv8AwDw8FgrA4ABQAAQBQDkGBwpAMArS4GZAS02uekSFcLQIyARnaT69IRluN3" +
            "/w/0CqEJGQQLO0AXTQMRvavwBUkZAMZ5tq+LWkX/jT+IYf+CvxQAD8DgAF8IEIDDGgC1mghk9qfJksgDYwC4DMBr7UWCDGXg" +
            "WP+Af8FQkJwAGECASlKDoRkArkxEQwhrPV74OAIAhkBVEpmEcQ4nnxazp/9bfx8fhoFngcQFUASgRwKLgBK1iRysT1ekAQpl" +
            "yASmbS69IjJc/gH+gVECBZ2CAAMAUQz4OIBGf4OABAApntXav4YBD+CvwbNAEtsIAmMYBKuZAk69/wCmNDGcw4b/1e//AcA" +
            "/4eH8Dw+g4cA/4e8GAxBwDAP+Eghwe/rffUFddczBYX9UFtdczBZ6oLa4pggln7v9QQigACAPff///Iuv//+Hg1YtQ3XXXXT" +
            "11109ddPXXXXXXXXXXXXT11109ddPXXXXXXXXXg";

    // Animated Pattern Test Card IDR Keyframe (320x240 H.264 Baseline)
    private static final String TESTSRC_IDR_B64 =
            "ZYiEBX///w9FAAFC3ycRBQDgACDCAAIInEAAEAwAFiAACDSAAIJkRBTHABv/8iEYQBVwALZFvbQCw72ftwAWP8nITlBTQIP1" +
            "0ABYv1pCX/u4nV/X/9H//8wfwgADQA480EAAIGIAAgdAAoAnAAx0tnok2AB+l7eQEgretStb9gbjtfQAHxI53QR2LJWxgAHi" +
            "OdsIyngv5gx1wwPoeoRmMAEKBhbl/D8NFFAAECiAILNoAkBz35ht5Fymw3wMAAfCAAEA0AEBYAsEAAIDIAAiFgJAAECDQZ4Q" +
            "igMq5WkAWXYMPoGPDFeHerP+wfZsGHJBPEQUA4AAgwAACCLxAABANABIgAAg0AACCbEQUxwA0n4f/4IjCgACkh44AA/rxwAB" +
            "JokniIKAcAAQYAABBF4gAAgGgAkQAAQaAABBNjIIYlJFsW//9f9AsDwAGlO1CovN77u/TpzQA3jIbCAAEAMAwWSCAAEAsAMC" +
            "zUABz/47Kcu7F+CSmHAAcarw7qIfthaQKM4fT9QAKnHkAAAEBjADrMofrGUNKf688AAQBYADml1mNMl3J/oYcAB5lNNSbjX" +
            "/Xm678///8EAAYAAUeBoIAAQLQABDBAB2cjSGl5FZyDCWHrjsQsA7xfgJQxP7jTmEcoI+lC0F1AMBediKGbSwhN9yyAnwXs" +
            "4H+F2H/4EQVxAADR0NAFCIKAcAAQYQABBE4gAAgGAAsQAAQaQABBMiIKY4ANJ11zMFHv/8vCHgAsP83ITnBzwOP11wAWH+b" +
            "kJzg54HH66ABY7U2nlsPzBhwgADQAUEiwQAAgZgACCAAAIAgAXgAfpeZ5AEg7+gAfpZmQBIO/81/RvVOpShAA8I52wjKJAl" +
            "E8e6AAsEcWMpzoJAv5w8+xkzMGK4gMQcckv//BWUAHh/ttPLX83wcOEAAaAwp4IAAQMwABAXACwTxbbG/cf2tgYfDCIQgEE" +
            "YWOLFCkDDmYJY0AauZgo1UFNdPXT11/gcP9gsBEGAonbXX6RZL//4YFAACAgAA4QXAAEGsAAQKiNXAIzZNDZ+VGAAQPq4Qhg" +
            "AGuCPABUGQQliTDQrTSKoAuDvAgAAsAgAAgb3JzPwOBzPEZm67+f//+PAFNXfU6+83XfH//9PuGC01d9N///3wGN13/X/we" +
            "Car6nb/fwCjdd/L/6CAAIehggABATCYA+RWK8ayCb/HgNVc1o0nIrFeNZBN/jEEfAtTCgboXbfz+IyEdnEbyyFtZhBHQPUwo" +
            "G4FW38M/qgrrrr/D/YihwFXAAtkW9tALDvZ+0DUSV6Rs0/X+AC0D/SRIxX8gwgCcdwQAAgCgCA4/AAxlWz0Sb2M0sgV6jjd" +
            "v0AB8TFO6CO5ZKWM6AQRq5x4bbXoHAYVib5f4KygAWEtxazUuFrPvwfP9tn7f//BhAAGgEFPBAACBuAAIDoAaCfEil7PDC2" +
            "SWGH4DcPba5DsECljJ0GHUE9PXMwUepgprp66euuKeOMkcDXhxxXAcDXhxxXAO9cHiYDvXB4mPgkP9AsBJ4Cg96WQl9KN2/" +
            "/oYQkZBAAR3PDA/Tht2H5Sx+GA/Ajs4MJ0ucVKlIcKfRAP2GzBwAPFrGjK0JFf+28Cqpp01f/fAT1NybUjf7BZV2qerv9gg" +
            "ADAECSIQAAgWgACAiABgFAhTLMJSFDwbRyeI4x+379g2VQhPOH+ZYXgTBilRqUUaFROhheoK666+1vImh5QWQAHyAoUlDpU" +
            "ydm94GsBt5G1D9q2f/jxy8OAAjgAnFZ+tkpUkSeAAAIBXg14NOdB2X0C3f/4MImAMMEAARCAIGREFsMc4Y5109czBRqoKa6" +
            "eunrrp6/0p8DpDALAAHIkBbv7fSYoaz/+D4QfC0j6Z8zljf/gwcAAgMC9zclkvPIflAGOHWXGcRsqJYEEpWCAALgMOXTBbX" +
            "W/a32YIUBZAATJhQ5IdWa/zoCAs7SeRGZe+/+gDFDjJjuK+FveAAkGwm9fFvdCUKNBsjKIAAIMAJ1BTXT1zMFHqYKa6eunr" +
            "rp6/yIP+wiCitKB4JT4TIAA5EgfbTrc27en/8HwsBIHSrrjZz+/4MMAB9wBK5eBP1FnDv/3wanD71RTnuQlZigtr/YQ+Xgm" +
            "Bd4iDEbNxLLlHLkPzMhkdTHQg6Fq4PgOAAZAGAksmJnqCmunrmYKNVBTXT109ddP//DDBaCjA4CAw9kHAALAACAaAKZCACZC" +
            "AACAAAVYHEIeWHEIeXgcACAoKlhwAICgqX//9gqhCaQQAEZ78OIQ8vw4AEBQVL9If+OKN90G64RC1UvZmGHAASIxFZDmUoy" +
            "VLSGDwOAAYAAEA8pBqC+vtD7Q+EwXQAc0KFrLi2MYlCS4NwAKf1IAwUiCG+g2meoKa6euZgo9TBTXT109ddPiIf/2CIFgcA" +
            "AwAAICYUyDgAGQABASBbL6OAA/j/9B+BwADAAAgJgpl/6fw8PwAcTkE+a1+sV2mlBh1BbXXT1109czBRqoKa6eunrrp/H4w" +
            "+wmCzA4ABkAAQEhLLDgAGAABATCmX/4/+FODgAGQABASIwOAAYAAEBMAMZc11BbXw/pD4IAWQAEGpw+9UU57kJWZBUy1BTX" +
            "T1zMFHqYKa6eunrrp//xHAGCAFmBwADIAAgJCWQcAAwAAICYKZf/AMcEPA4ABkAAQEgWy//7eCGAAkGwm9fFvdCUKNDB6gt" +
            "r40/0pgnBdA4ABkAYCUzAB8nidC6xW3gAD7gF3Lwb96TBn/4PRFAAeL/AcidGffvTPUFNdPXMwUaqCmunrp666f//9ArBQO" +
            "BAciHAALgACAY5lxACZCAACACAVAchDSw5CGl+BwAEBQXLDgAICguX/w//BVAwABMPAQIABgFXDkIaX8OAAgKC5f/tb7WsYY" +
            "AEvJgMCZhhKT/3gCTEM1/E1aPwskPgAvIE6BVlHEnlHq95FDjJDucWEtWGHUF9f6RT6UhQFgAicgnzlk6pXaaUGGAA6EWC9" +
            "i67f1zP90JIB8TdwLSnUh9+gDkgbrhkNZTdm0y1BTXT1zMFHqYKa6eunrrp75mCh79rF2t+EyQBGhw+dcW5zEoWX4zQNl4" +
            "iWtpmT//wAH1AEjk4F/EUcP//B6Ehxn3Fu2UIT9//UFvx8IfhoFGJAAECLiT8T4k8DgAHGAAEBkJAAEB0wAAg+AOOAClIHBo" +
            "CRS5LwHAA0AAQAYpA4AaAA8Uj8v//+g1xJ4n4Yk8SeByIFCSBAUruFwMpF8i5qQO4KUi8vpw/+g1AAdoABcM5kdOqrGf/gx" +
            "Z4n8/EnhAAgkrCAAOA4MG02kDg0BIpSXi+pA4BoACxSLy/gGAf6DXEniT8T4uBwsOEiMBxfUhcMpTIuakLgGUi8v4BgH+g1" +
            "3FeKxDwJQgkIQbWKLdxc1IHHApSLMv4BgH+g1xWK8Q9wMAAS4BAkAAQahAAYDoAjIOgIzdg4AB2gACCFFIHAAP0AAQSopFmW" +
            "fAMA/0GuKxXisUYEoIJEINbECZexc1IHHApSLy/gGAf6DXFYrxXCASBRAQABwHBg+2A7AK0nLYuakOAQAOZLORagBxubYQ6C" +
            "gxRQwYoPSB8B2kWMsaYt/H4f0Guq9QoxRgeHFB6QPgO0i2WNNPhAMA/0Guq6woAAg6igALDAALQaDgACABAABBNmkDgACAFA" +
            "ABBVmkWMtg4gAjIOIAIz8AwD/Qa6rpiB4UM4CFYoPSB8B2kWO0y0ivgGAf6DXEA8QPxA8QPCBwccEAAIAgAcz0LSFgA2lZbB" +
            "j8DgAEIAAICM0i2W+AYB/oNcQPEA/EDxA8DAuGChnAgq4zEAbhdij0gfB2kWy3wDAP9BriB4gfiAeIHgYBcGCjOBELSB8O0i" +
            "3YoPSB8DtItlvgGAf6DXEDxR6imIABAIgoAAgqHAAEFwOgFZB0AjNyDgAG4AAIG00gcAAzAABAumkWy3wDAP9Bri4vifUCoY" +
            "UMUITEkysig9IHwDtIsZY+A2D/oNcXeABZOLxGW/bRCk/9CfBQwARPYQAAgCAByrTBxAARlZFB6QOAASgAAgJzSLZb/w/0Gv" +
            "jGvKVzve5V/DAxP6i4FQMKGKOTEkysig9IHwHaRbLGLUAe7cKkEoEHDFgKOcLgZSkvDz5B4zN4piTcejda/Gu2J7WvjXN7q1" +
            "///9gs15qqq+NZQZNA33Iw7GCkiAHiSQuNEsLOLaqqrWqqq1yfRUT3jSRPliX9axxIMZB73+94pwbkx+p2Fewtf//2Gq9352" +
            "Xl1rMQgaFMaNcViNl3d/GuPoNJvd7wsyio5ISQd7eSEkLe3f97lJNykhlkEkpI+FnFt7/e/x2SaEmhJ3ud9wD//hoUA4BDnF" +
            "ywV6o/SkZklR4GAAMnAAEDIKAAICwoAAg6gwBAvxgAgPA/AsAAWAAEASMBgABIAAkD///+g1CvVBkqjwdWQzJVHgYYwpoaOM" +
            "Yjg/FRhjEHB//IfDoNECvVCWPwQEw8kUIcnhCgABAGAHCAAMAKABoED3QZC/xVhjAAC4AAgBwP4LMAMIZmBodSkYAVdE+iBm" +
            "Vb7qu7rv/4+xszBtf9a5/v4DgPeu7rv/7Rtd/Wu//gYFVXd3P/+BACYBxwQAAgEAAOABQCYC7LR7pYBiXJlsQcNn8iocw9EE" +
            "3+AAjBwS6Jnl0q3cZkMzuM3lPBgJXB4wj8EuYWDNi7b+f45/Q0riXMOjLHP/wFZl39a7//4YYAAgG8ACQkAAQXjAACCmVZZ" +
            "MZYDmmgSp+2AC6QFgAeWcjDGCBwfGNjiLGoI44vcBzAgAAqCgACAtL4D/+g1AcCOUXLK/eTLFX7wOAA0AAQHAoAAgt4AARoB" +
            "xAPQcQHvCgAAgIgACCPGAUAAEBMAAQT4w///+g1SjLPziGgdGW4CwoecR0QdwojmAUEBzD/4f9Boh7l4DkGMTLfCJgACAKA" +
            "IEAAVCAAIA+mDUH/iowxwAgBN///0GtblJEzUJFyCOhbjGwqP628WoD0lLBIuh0NljGkFR+2feQh//YaKA4IhKpad0luEgAC" +
            "BAEAAEKEMAAQI6AAIdULgAFgABAGrBOAAaAAEBCsCzLMoAAbAAEA+WUAANgACAjL8LqAEL13R/+/1o25sN//CRdkbLGVIL/4" +
            "73j0Mh8Og1CW6Aszo9a1KltZwg0AFAoIAAQAQBAABAGAbFuMag6PxbjC4ABwAAQEpw+P//Qamt0BZnR5rOgHuY/A5yCRdEiM" +
            "bFR+LHGNIKj///6DU1ugLMdHohGFmdHgc5BJMhbjGwqPxY4xpAqP6CH/8g6DiIqSxK+rmQt9+AAzaJMb9CI7oGAAIDcAHhCA" +
            "AIK4QAARGgcGgMBVODggMBEKBxAAEAcyABej/qAAEAmjQADoAAgR0MaAAZAAEBmhlQAUkAXSMaxmyurm4cgIiEvGkkNGWgArp" +
            "JmbQsq4AEZSzNoW7sH4kqr7f8CgDHV/j///okqr7ff/zxJVX2/4MIAAqAAICZxoQAAgAAADAgwACAAIgQvQAEAAIAn28GaQU" +
            "qwMMv8oYVRLT0FNkGAEZM2iKYwUGw0Qn49DZ29WY5w/YAVA84WXlBkggQQPww/6DURxeA4JhyJZmcIFgDBYQABIUABYHN9B4" +
            "FvcRjQAQA8j/+H/QaqFvuS2hmcRaA/E2JHcW4xsKj8WqDLAYYxCkq4Yjg/O6cdzQVigACANGgDGADf//+w+ChPtApzOevxXj" +
            "gVV1Xdz///wFbXf1rv///PVd2P//4cCRT1pHqRS773+AB453qLa9Kj9d8IAAqggsIAAQHgoeCkiocw9EE3+AhTLMJSFDwizL" +
            "INCFDGEfglzCwZsXbfzYNFcITzh6DbS8C4lRwhPPHIttL/5H7BaUBr9OnNIHf7WRjwAhu0C5urT7e4IAA8Aw9sIAAQBgBg8v" +
            "8CY0AR8Z/wGJAYZd/wJQ8DNxn3YcqHmbvSBPMwUF9WgU0sU88kLtwcPYJ24OHsCQGESAwrmYKLxUEIoABiIpOKsVf//+6BcH" +
            "MAJtFjVk67QLc+KJGPf84IAAwBRLAQAB4Bx73ACwCLu/QIWF08+cAFgQX9TkwBTgVYp8ieQ/xpuABbIt7aAWHez9gBjq/x//" +
            "/oSVXzn/f8ZMsdfSsj//2gUAFk9/41QtpR/v+QAAB6HA4AIHABwQAAgZAACBQAAIHgA2ACMVm9EmyRFESiyeWq7MfNsDcVff" +
            "BgBFIwysMzFlrY7ALwC/D30CKBJ137Q3X5AyIUCFDBRwcof/4KgTh5RAEjD7/f9t/zMElpECmvA";

    private static byte[] spsBytes;
    private static byte[] ppsBytes;
    private static byte[] smpteIdrBytes;
    private static byte[] testsrcIdrBytes;
    private static final List<RtpPayload> preSmptePackets = new ArrayList<>();
    private static final List<RtpPayload> preTestsrcPackets = new ArrayList<>();

    static {
        try {
            spsBytes = Base64.decode(SPS_B64, Base64.DEFAULT);
            ppsBytes = Base64.decode(PPS_B64, Base64.DEFAULT);
            smpteIdrBytes = Base64.decode(SMPTE_IDR_B64, Base64.DEFAULT);
            testsrcIdrBytes = Base64.decode(TESTSRC_IDR_B64, Base64.DEFAULT);

            preSmptePackets.addAll(packetizeNal(spsBytes, false));
            preSmptePackets.addAll(packetizeNal(ppsBytes, false));
            preSmptePackets.addAll(packetizeNal(smpteIdrBytes, true));

            preTestsrcPackets.addAll(packetizeNal(spsBytes, false));
            preTestsrcPackets.addAll(packetizeNal(ppsBytes, false));
            preTestsrcPackets.addAll(packetizeNal(testsrcIdrBytes, true));
        } catch (Exception e) {
            Log.e(TAG, "Failed to decode static NAL units", e);
        }
    }

    public static class RtpPayload {
        public final byte[] payload;
        public final boolean marker;

        public RtpPayload(byte[] payload, boolean marker) {
            this.payload = payload;
            this.marker = marker;
        }
    }

    /**
     * Packetizes an H.264 NAL unit into RFC 6184 RTP video payloads.
     * Handles both single NAL unit packets and FU-A fragmentation for packets exceeding MTU.
     */
    public static List<RtpPayload> packetizeNal(byte[] nal, boolean isLastNal) {
        List<RtpPayload> list = new ArrayList<>();
        if (nal == null || nal.length == 0) return list;

        if (nal.length <= MAX_RTP_PAYLOAD) {
            list.add(new RtpPayload(nal, isLastNal));
            return list;
        }

        // RFC 6184 Section 5.8: FU-A Fragmentation
        byte nalHeader = nal[0];
        int nalRefIdc = nalHeader & 0x60;
        int nalType = nalHeader & 0x1F;
        byte fuIndicator = (byte) (nalRefIdc | 28); // 28 = FU-A

        int offset = 1;
        int remaining = nal.length - 1;
        boolean isFirst = true;

        while (remaining > 0) {
            int chunkSize = Math.min(remaining, MAX_RTP_PAYLOAD);
            boolean isLast = (chunkSize == remaining);

            int sBit = isFirst ? 0x80 : 0x00;
            int eBit = isLast ? 0x40 : 0x00;
            byte fuHeader = (byte) (sBit | eBit | nalType);

            byte[] chunk = new byte[2 + chunkSize];
            chunk[0] = fuIndicator;
            chunk[1] = fuHeader;
            System.arraycopy(nal, offset, chunk, 2, chunkSize);

            boolean marker = (isLast && isLastNal);
            list.add(new RtpPayload(chunk, marker));

            offset += chunkSize;
            remaining -= chunkSize;
            isFirst = false;
        }

        return list;
    }

    /**
     * Returns compliant H.264 Baseline NAL units (SPS, PPS, IDR) for a given frame index.
     * Alternates frames cleanly to demonstrate live real-time screen motion to Discord.
     */
    public static List<byte[]> getFrameNals(int frameIndex) {
        List<byte[]> list = new ArrayList<>();
        if (spsBytes != null) list.add(spsBytes);
        if (ppsBytes != null) list.add(ppsBytes);

        // Every 30 frames alternate between SMPTE Broadcast bars and Test pattern
        byte[] idr = ((frameIndex / 30) % 2 == 0) ? smpteIdrBytes : testsrcIdrBytes;
        if (idr != null) {
            list.add(idr);
        }
        return list;
    }

    /**
     * High-performance zero-allocation RTP payload provider.
     * Returns pre-packetized RFC 6184 H.264 payloads for maximum 30 FPS efficiency.
     */
    public static List<RtpPayload> getFramePackets(int frameIndex) {
        return ((frameIndex / 30) % 2 == 0) ? preSmptePackets : preTestsrcPackets;
    }

    /**
     * Converts an Android ARGB_8888 Bitmap into NV21 / YUV420 semi-planar bytes for MediaCodec.
     */
    public static byte[] bitmapToYuv420(Bitmap bitmap, int width, int height) {
        if (bitmap == null) return null;
        int[] argb = new int[width * height];
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap, width, height, false);
        scaled.getPixels(argb, 0, width, 0, 0, width, height);

        byte[] yuv = new byte[width * height * 3 / 2];
        int yIndex = 0;
        int uvIndex = width * height;

        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int color = argb[j * width + i];
                int r = (color >> 16) & 0xFF;
                int g = (color >> 8) & 0xFF;
                int b = color & 0xFF;

                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[yIndex++] = (byte) Math.max(0, Math.min(255, y));

                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, v)); // NV21: V then U
                    yuv[uvIndex++] = (byte) Math.max(0, Math.min(255, u));
                }
            }
        }
        if (scaled != bitmap) scaled.recycle();
        return yuv;
    }
}
