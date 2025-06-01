package com.xiaozhi.utils;

import io.github.jaredmdobson.concentus.OpusEncoder;
import io.github.jaredmdobson.concentus.OpusApplication;
import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusSignal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.PreDestroy;
import java.nio.file.Files;

@Component
public class OpusProcessor {
    private static final Logger logger = LoggerFactory.getLogger(OpusProcessor.class);

    // 缓存
    private final ConcurrentHashMap<String, OpusDecoder> decoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, OpusEncoder> encoders = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, short[]> overlaps = new ConcurrentHashMap<>();

    // 常量
    private static final int FRAME_SIZE = 960;
    private static final int SAMPLE_RATE = AudioUtils.SAMPLE_RATE;
    private static final int CHANNELS = AudioUtils.CHANNELS;
    public static final int OPUS_FRAME_DURATION_MS = AudioUtils.OPUS_FRAME_DURATION_MS;
    private static final int MAX_SIZE = 1275;

    // 预热帧数量 - 添加几个静音帧来预热编解码器
    private static final int PREWARM_FRAMES = 2;

    /**
     * PCM转MP3字节数组
     * 
     * @param pcmData PCM音频数据
     * @return MP3格式的字节数组
     */
    public byte[] pcmToMp3(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return new byte[0];
        }
        
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String tempPcmPath = AudioUtils.AUDIO_PATH + uuid + ".pcm";
        String tempMp3Path = AudioUtils.AUDIO_PATH + uuid + ".mp3";
        
        try {
            // 确保音频目录存在
            Files.createDirectories(Paths.get(AudioUtils.AUDIO_PATH));
            
            // 先将PCM数据写入临时文件
            try (FileOutputStream fos = new FileOutputStream(tempPcmPath)) {
                fos.write(pcmData);
            }
            
            // 构建ffmpeg命令：将PCM转换为MP3
            String[] command = {
                    "ffmpeg",
                    "-f", "s16le", // 输入格式：16位有符号小端序PCM
                    "-ar", String.valueOf(SAMPLE_RATE), // 采样率
                    "-ac", String.valueOf(CHANNELS), // 声道数
                    "-i", tempPcmPath, // 输入文件
                    "-b:a", String.valueOf(AudioUtils.BITRATE), // 比特率
                    "-f", "mp3", // 输出格式
                    "-q:a", "0", // 最高质量
                    tempMp3Path // 输出文件
            };
            
            // 执行命令
            Process process = Runtime.getRuntime().exec(command);
            
            // 读取错误输出以便调试
            StringBuilder errorOutput = new StringBuilder();
            try (InputStream errorStream = process.getErrorStream()) {
                byte[] buffer = new byte[1024];
                int bytesRead;
                while ((bytesRead = errorStream.read(buffer)) != -1) {
                    errorOutput.append(new String(buffer, 0, bytesRead));
                }
            }
            
            // 等待进程完成
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                logger.error("ffmpeg转换失败，退出代码: {}，错误信息: {}", exitCode, errorOutput.toString());
                return new byte[0];
            }
            
            // 检查输出文件是否存在
            if (!Files.exists(Paths.get(tempMp3Path))) {
                logger.error("ffmpeg转换后的MP3文件不存在");
                return new byte[0];
            }
            
            // 读取MP3文件内容
            byte[] mp3Data = Files.readAllBytes(Paths.get(tempMp3Path));
            return mp3Data;
            
        } catch (IOException | InterruptedException e) {
            logger.error("PCM转MP3时发生错误", e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new byte[0];
        } finally {
            // 删除临时文件
            try {
                Files.deleteIfExists(Paths.get(tempPcmPath));
                Files.deleteIfExists(Paths.get(tempMp3Path));
            } catch (IOException e) {
                logger.warn("删除临时文件失败", e);
            }
        }
    }

    // Opus转wav字节数组
    public byte[] pcmToWav(byte[] pcmData) {
        if (pcmData == null || pcmData.length == 0) {
            return new byte[0];
        }

        try {
            // 创建WAV文件头
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            DataOutputStream dos = new DataOutputStream(outputStream);

            // WAV文件参数
            int bitsPerSample = 16; // 16位采样

            // RIFF头
            dos.writeBytes("RIFF");
            dos.writeInt(Integer.reverseBytes(36 + pcmData.length)); // 文件长度
            dos.writeBytes("WAVE");

            // fmt子块
            dos.writeBytes("fmt ");
            dos.writeInt(Integer.reverseBytes(16)); // 子块大小
            dos.writeShort(Short.reverseBytes((short) 1)); // 音频格式 (1 = PCM)
            dos.writeShort(Short.reverseBytes((short) CHANNELS)); // 通道数
            dos.writeInt(Integer.reverseBytes(SAMPLE_RATE)); // 采样率
            dos.writeInt(Integer.reverseBytes(SAMPLE_RATE * CHANNELS * bitsPerSample / 8)); // 字节率
            dos.writeShort(Short.reverseBytes((short) (CHANNELS * bitsPerSample / 8))); // 块对齐
            dos.writeShort(Short.reverseBytes((short) bitsPerSample)); // 每个样本的位数

            // data子块
            dos.writeBytes("data");
            dos.writeInt(Integer.reverseBytes(pcmData.length)); // 数据大小

            // 写入PCM数据
            dos.write(pcmData);

            return outputStream.toByteArray();
        } catch (Exception e) {
            logger.error("pcm转WAV失败", e);
            return new byte[0];
        }
    }

    /**
     * Opus转PCM字节数组
     */
    public byte[] opusToPcm(String sid, byte[] data) throws OpusException {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        try {
            OpusDecoder decoder = getDecoder(sid);
            short[] buf = new short[FRAME_SIZE * 12];
            int samples = decoder.decode(data, 0, data.length, buf, 0, buf.length, false);

            byte[] pcm = new byte[samples * 2];
            for (int i = 0; i < samples; i++) {
                pcm[i * 2] = (byte) (buf[i] & 0xFF);
                pcm[i * 2 + 1] = (byte) ((buf[i] >> 8) & 0xFF);
            }

            return pcm;
        } catch (OpusException e) {
            logger.warn("解码失败: {}", e.getMessage());
            resetDecoder(sid);
            throw e;
        }
    }

    /**
     * Opus转short数组
     */
    public short[] opusToShort(String sid, byte[] data) throws OpusException {
        if (data == null || data.length == 0) {
            return new short[0];
        }

        try {
            OpusDecoder decoder = getDecoder(sid);
            short[] buf = new short[FRAME_SIZE * 6];
            int samples = decoder.decode(data, 0, data.length, buf, 0, buf.length, false);

            if (samples < buf.length) {
                short[] result = new short[samples];
                System.arraycopy(buf, 0, result, 0, samples);
                return result;
            }

            return buf;
        } catch (OpusException e) {
            logger.warn("解码失败: {}", e.getMessage());
            resetDecoder(sid);
            throw e;
        }
    }

    /**
     * OGG转PCM
     */
    public byte[] oggToPcm(String sid, byte[] data) {
        if (data == null || data.length == 0) {
            return new byte[0];
        }

        try {
            // 检查OGG格式
            if (data.length < 4 || data[0] != 'O' || data[1] != 'g' || data[2] != 'g' || data[3] != 'S') {
                try {
                    // 尝试直接解码
                    return opusToPcm(sid, data);
                } catch (OpusException e) {
                    logger.warn("非OGG格式解码失败: {}", e.getMessage());
                    return new byte[0];
                }
            }

            // 解析OGG
            List<byte[]> packets = parseOgg(data);

            if (packets.isEmpty()) {
                logger.warn("OGG中无数据包");
                return new byte[0];
            }

            // 解码所有包
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int count = 0;

            for (byte[] packet : packets) {
                try {
                    // 跳过Opus头
                    if (packet.length > 8 && packet[0] == 'O' && packet[1] == 'p'
                            && packet[2] == 'u' && packet[3] == 's') {
                        continue;
                    }

                    byte[] pcm = opusToPcm(sid, packet);
                    if (pcm.length > 0) {
                        out.write(pcm);
                        count++;
                    }
                } catch (OpusException e) {
                    logger.warn("包解码失败: {}", e.getMessage());
                }
            }

            return out.toByteArray();

        } catch (Exception e) {
            logger.error("OGG解码错误", e);
            return new byte[0];
        }
    }

    /**
     * 解析OGG格式
     */
    private List<byte[]> parseOgg(byte[] data) {
        List<byte[]> packets = new ArrayList<>();
        ByteArrayInputStream in = new ByteArrayInputStream(data);

        byte[] pattern = new byte[4];
        byte[] segments = new byte[255];

        while (in.available() > 0) {
            try {
                // 读取OGG标识
                if (in.read(pattern, 0, 4) < 4)
                    break;

                // 检查标识
                if (pattern[0] != 'O' || pattern[1] != 'g' || pattern[2] != 'g' || pattern[3] != 'S') {
                    // 查找下一个标识
                    int b;
                    boolean found = false;
                    while ((b = in.read()) != -1) {
                        if (b == 'O') {
                            if (in.read(pattern, 0, 3) < 3)
                                break;
                            if (pattern[0] == 'g' && pattern[1] == 'g' && pattern[2] == 'S') {
                                found = true;
                                break;
                            }
                        }
                    }
                    if (!found)
                        break;
                }

                // 跳过版本和标志
                in.skip(2);

                // 跳过粒度位置、序列号、页序号、校验和
                in.skip(16);

                // 读取分段数
                int segCount = in.read();
                if (segCount == -1)
                    break;

                // 读取分段表
                if (in.read(segments, 0, segCount) < segCount)
                    break;

                // 计算数据长度
                int dataLen = 0;
                for (int i = 0; i < segCount; i++) {
                    dataLen += segments[i] & 0xFF;
                }

                // 读取数据
                byte[] pageData = new byte[dataLen];
                int read = in.read(pageData, 0, dataLen);
                if (read < dataLen) {
                    byte[] actual = new byte[read];
                    System.arraycopy(pageData, 0, actual, 0, read);
                    pageData = actual;
                }

                // 解析数据包
                int offset = 0;
                int packetLen = 0;

                for (int i = 0; i < segCount; i++) {
                    int segLen = segments[i] & 0xFF;
                    packetLen += segLen;

                    // 如果段长不是255，表示包结束
                    if (segLen != 255) {
                        if (packetLen > 0 && offset + packetLen <= pageData.length) {
                            byte[] packet = new byte[packetLen];
                            System.arraycopy(pageData, offset, packet, 0, packetLen);
                            packets.add(packet);
                        }
                        offset += packetLen;
                        packetLen = 0;
                    }
                }

                // 处理最后一个包
                if (packetLen > 0 && offset + packetLen <= pageData.length) {
                    byte[] packet = new byte[packetLen];
                    System.arraycopy(pageData, offset, packet, 0, packetLen);
                    packets.add(packet);
                }
            } catch (Exception e) {
                logger.warn("OGG解析错误: {}", e.getMessage());
            }
        }

        return packets;
    }

    /**
     * 读取Opus文件
     */
    public List<byte[]> readOpus(File file) throws IOException {
        List<byte[]> frames = new ArrayList<>();

        // 检查文件大小
        long size = file.length();
        if (size <= 0) {
            logger.warn("空文件: {}", file.getPath());
            return frames;
        }

        try (FileInputStream fis = new FileInputStream(file)) {
            // 检查文件格式
            byte[] header = new byte[8];
            fis.read(header, 0, Math.min(8, (int) size));
            fis.getChannel().position(0);

            // 检查OGG格式
            if (isOgg(header)) {
                return readOgg(file);
            }

            // 检查原始Opus格式
            if (isOpusHead(header)) {
                return readRaw(fis);
            }

            // 尝试帧格式
            frames = readFramed(fis);
            if (!frames.isEmpty()) {
                return frames;
            }

            return readWhole(file);
        } catch (Exception e) {
            logger.error("读取失败: {}", file.getName(), e);
            return readWhole(file);
        }
    }

    /**
     * 检查OGG格式
     */
    private boolean isOgg(byte[] data) {
        return data.length >= 4 &&
                data[0] == 'O' && data[1] == 'g' &&
                data[2] == 'g' && data[3] == 'S';
    }

    /**
     * 检查Opus头
     */
    private boolean isOpusHead(byte[] data) {
        return data.length >= 8 &&
                data[0] == 'O' && data[1] == 'p' &&
                data[2] == 'u' && data[3] == 's' &&
                data[4] == 'H' && data[5] == 'e' &&
                data[6] == 'a' && data[7] == 'd';
    }

    /**
     * 读取OGG文件
     */
    private List<byte[]> readOgg(File file) throws IOException {
        // 使用Files.readAllBytes代替FileInputStream.readAllBytes
        byte[] data = Files.readAllBytes(file.toPath());
        return parseOgg(data);
    }

    /**
     * 读取原始Opus文件
     */
    private List<byte[]> readRaw(FileInputStream fis) throws IOException {
        List<byte[]> frames = new ArrayList<>();

        // 跳过头部
        fis.skip(19);

        byte[] buffer = new byte[4096];
        int read;

        while ((read = fis.read(buffer)) > 0) {
            byte[] frame = new byte[read];
            System.arraycopy(buffer, 0, frame, 0, read);
            frames.add(frame);
        }

        return frames;
    }

    /**
     * 读取帧格式文件
     */
    private List<byte[]> readFramed(FileInputStream fis) throws IOException {
        // 尝试2字节帧头
        fis.getChannel().position(0);
        List<byte[]> frames = readFrames(fis, 2);

        if (!frames.isEmpty()) {
            logger.info("2字节帧头成功: {} 帧", frames.size());
            return frames;
        }

        // 尝试4字节帧头
        fis.getChannel().position(0);
        frames = readFrames(fis, 4);

        if (!frames.isEmpty()) {
            logger.info("4字节帧头成功: {} 帧", frames.size());
            return frames;
        }

        // 尝试固定大小帧
        fis.getChannel().position(0);
        frames = readFixed(fis, 80);

        if (!frames.isEmpty()) {
            logger.info("固定帧成功: {} 帧", frames.size());
        }

        return frames;
    }

    /**
     * 读取带帧头的文件
     */
    private List<byte[]> readFrames(FileInputStream fis, int headerSize) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        byte[] buffer = new byte[MAX_SIZE];
        byte[] sizeBytes = new byte[headerSize];
        int good = 0;
        int bad = 0;

        while (fis.read(sizeBytes, 0, headerSize) == headerSize) {
            int frameSize = getFrameSize(sizeBytes, headerSize);

            // 检查帧大小
            if (frameSize <= 0 || frameSize > MAX_SIZE) {
                bad++;
                if (bad > 3)
                    break;
                continue;
            }

            // 读取帧
            int read = fis.read(buffer, 0, frameSize);
            if (read != frameSize)
                break;

            byte[] frame = new byte[frameSize];
            System.arraycopy(buffer, 0, frame, 0, frameSize);
            frames.add(frame);
            good++;

            // 5个有效帧就认为格式正确
            if (good >= 5)
                return frames;
        }

        // 帧太少，可能不是正确格式
        if (good < 5)
            frames.clear();

        return frames;
    }

    /**
     * 获取帧大小
     */
    private int getFrameSize(byte[] bytes, int size) {
        if (size == 2) {
            return ((bytes[1] & 0xFF) << 8) | (bytes[0] & 0xFF);
        } else if (size == 4) {
            return ((bytes[3] & 0xFF) << 24) |
                    ((bytes[2] & 0xFF) << 16) |
                    ((bytes[1] & 0xFF) << 8) |
                    (bytes[0] & 0xFF);
        }
        return -1;
    }

    /**
     * 读取固定大小帧
     */
    private List<byte[]> readFixed(FileInputStream fis, int frameSize) throws IOException {
        List<byte[]> frames = new ArrayList<>();
        byte[] buffer = new byte[frameSize];

        int read;
        while ((read = fis.read(buffer)) == frameSize) {
            byte[] frame = new byte[frameSize];
            System.arraycopy(buffer, 0, frame, 0, frameSize);
            frames.add(frame);

            // 足够多的帧
            if (frames.size() >= 5)
                return frames;
        }

        // 处理最后一帧
        if (read > 0) {
            byte[] last = new byte[read];
            System.arraycopy(buffer, 0, last, 0, read);
            frames.add(last);
        }

        return frames;
    }

    /**
     * 读取整个文件作为单帧
     */
    private List<byte[]> readWhole(File file) throws IOException {
        List<byte[]> frames = new ArrayList<>();

        // 使用Files.readAllBytes代替FileInputStream.readAllBytes
        byte[] data = Files.readAllBytes(file.toPath());
        if (data.length > 0) {
            frames.add(data);
            logger.info("整个文件作为单帧: {} 字节", data.length);
        }

        return frames;
    }

    /**
     * 获取解码器
     */
    public OpusDecoder getDecoder(String sid) {
        return decoders.computeIfAbsent(sid, k -> {
            try {
                OpusDecoder decoder = new OpusDecoder(SAMPLE_RATE, CHANNELS);
                decoder.setGain(0);
                return decoder;
            } catch (OpusException e) {
                logger.error("创建解码器失败", e);
                throw new RuntimeException("创建解码器失败", e);
            }
        });
    }

    /**
     * 重置解码器
     */
    public void resetDecoder(String sid) {
        decoders.remove(sid);
        try {
            getDecoder(sid);
        } catch (Exception e) {
            logger.error("重置解码器失败", e);
        }
    }

    /**
     * PCM转Opus
     */
    public List<byte[]> pcmToOpus(String sid, byte[] pcm) throws OpusException {
        if (pcm == null || pcm.length == 0) {
            return new ArrayList<>();
        }

        // 确保PCM长度是偶数
        int pcmLen = pcm.length;
        if (pcmLen % 2 != 0) {
            pcmLen--;
        }

        // 每帧样本数
        int frameSize = (SAMPLE_RATE * OPUS_FRAME_DURATION_MS) / 1000;

        // 确保frameSize与FRAME_SIZE一致
        if (frameSize != FRAME_SIZE) {
            frameSize = FRAME_SIZE; // 使用常量值
        }

        // 获取编码器
        OpusEncoder encoder = getEncoder(sid, SAMPLE_RATE, CHANNELS);

        // 处理PCM
        List<byte[]> frames = new ArrayList<>();

        // 字节序处理
        ByteBuffer pcmBuf = ByteBuffer.wrap(pcm, 0, pcmLen);
        pcmBuf.order(ByteOrder.LITTLE_ENDIAN);
        ShortBuffer shorts = pcmBuf.asShortBuffer();

        int totalShorts = pcmLen / 2;
        int frameCount = (totalShorts + frameSize - 1) / frameSize;

        // 缓冲区
        short[] shortBuf = new short[frameSize];
        byte[] opusBuf = new byte[MAX_SIZE];

        // 添加预热帧 - 渐入效果，解决开头破音问题
        addPrewarmFrames(frames, encoder, frameSize, opusBuf);

        // 添加淡入效果到第一帧
        if (totalShorts > 0) {
            // 创建第一帧的拷贝，并应用淡入效果
            short[] firstFrameBuf = new short[frameSize];
            Arrays.fill(firstFrameBuf, (short) 0);

            int firstFrameSamples = Math.min(frameSize, totalShorts);
            shorts.position(0);
            shorts.get(firstFrameBuf, 0, firstFrameSamples);

            // 应用淡入效果 - 前20毫秒（大约320个样本）
            int fadeInSamples = Math.min(320, firstFrameSamples);
            for (int i = 0; i < fadeInSamples; i++) {
                // 线性淡入
                float gain = (float) i / fadeInSamples;
                firstFrameBuf[i] = (short) (firstFrameBuf[i] * gain);
            }

            try {
                // 编码第一帧
                int opusLen = encoder.encode(firstFrameBuf, 0, frameSize, opusBuf, 0, opusBuf.length);
                if (opusLen > 0) {
                    byte[] frame = new byte[opusLen];
                    System.arraycopy(opusBuf, 0, frame, 0, opusLen);
                    frames.add(frame);
                }
            } catch (OpusException e) {
                logger.warn("淡入帧编码失败: {}", e.getMessage());
            }

            // 从第二帧开始编码剩余的帧
            for (int i = 1; i < frameCount; i++) {
                // 当前帧起始位置
                int start = i * frameSize;

                // 当前帧样本数
                int samples = Math.min(frameSize, totalShorts - start);

                // 重置缓冲区
                Arrays.fill(shortBuf, (short) 0);

                // 读取样本
                if (start < totalShorts) {
                    shorts.position(start);
                    int actual = Math.min(samples, shorts.remaining());
                    shorts.get(shortBuf, 0, actual);

                    try {
                        // 编码
                        int opusLen = encoder.encode(shortBuf, 0, frameSize, opusBuf, 0, opusBuf.length);

                        if (opusLen > 0) {
                            // 创建帧
                            byte[] frame = new byte[opusLen];
                            System.arraycopy(opusBuf, 0, frame, 0, opusLen);
                            frames.add(frame);
                        }
                    } catch (OpusException e) {
                        logger.warn("帧 #{} 编码失败: {}", i, e.getMessage());
                    }
                }
            }
        }

        return frames;
    }

    /**
     * 添加预热帧 - 解决开头破音问题
     */
    private void addPrewarmFrames(List<byte[]> frames, OpusEncoder encoder, int frameSize, byte[] opusBuf) {
        // 创建静音帧
        short[] silenceBuf = new short[frameSize];
        Arrays.fill(silenceBuf, (short) 0);

        // 添加几个静音帧来预热编码器
        for (int i = 0; i < PREWARM_FRAMES; i++) {
            try {
                int opusLen = encoder.encode(silenceBuf, 0, frameSize, opusBuf, 0, opusBuf.length);
                if (opusLen > 0) {
                    byte[] frame = new byte[opusLen];
                    System.arraycopy(opusBuf, 0, frame, 0, opusLen);
                    frames.add(frame);
                }
            } catch (OpusException e) {
                logger.warn("预热帧 #{} 编码失败: {}", i, e.getMessage());
            }
        }
    }

    /**
     * 清理会话
     */
    public void cleanup(String sid) {
        decoders.remove(sid);
        overlaps.remove(sid);

        // 清理编码器
        List<String> toRemove = new ArrayList<>();
        for (String key : encoders.keySet()) {
            if (key.startsWith(sid + "_")) {
                toRemove.add(key);
            }
        }

        for (String key : toRemove) {
            encoders.remove(key);
        }
    }

    /**
     * 获取编码器
     */
    private OpusEncoder getEncoder(String sid, int rate, int channels) {
        String key = sid + "_" + rate + "_" + channels;
        return encoders.computeIfAbsent(key, k -> {
            try {
                OpusEncoder encoder = new OpusEncoder(rate, channels, OpusApplication.OPUS_APPLICATION_VOIP);

                // 优化设置
                encoder.setBitrate(AudioUtils.BITRATE);
                // 这里后续看是不是要针对音乐做一个切换
                encoder.setSignalType(OpusSignal.OPUS_SIGNAL_VOICE);
                encoder.setComplexity(5); // 复杂度高音质好，低速度快
                encoder.setPacketLossPercent(0); // 降低丢包补偿，减少处理延迟
                encoder.setForceChannels(channels);
                encoder.setUseVBR(false); // 使用CBR模式确保稳定的比特率
                encoder.setUseDTX(false); // 禁用DTX以确保连续的帧

                return encoder;
            } catch (OpusException e) {
                logger.error("创建编码器失败: 采样率={}, 通道={}", rate, channels, e);
                throw new RuntimeException("创建编码器失败", e);
            }
        });
    }

    /**
     * 释放资源
     */
    @PreDestroy
    public void cleanup() {
        decoders.clear();
        encoders.clear();
        overlaps.clear();
    }
}