import { Config } from "@remotion/cli/config";

Config.setEntryPoint("src/index.ts");
Config.setVideoImageFormat("jpeg");
Config.setJpegQuality(95);
Config.setCodec("h264");
Config.setPixelFormat("yuv420p");
Config.setColorSpace("bt709");
Config.setCrf(16);
Config.setX264Preset("slow");
Config.setAudioCodec("aac");
Config.setAudioBitrate("320k");
// Three takes at once in the lineup (the tablet's is 1920 px wide), each a frame held and the next decoding.
Config.setOffthreadVideoCacheSizeInBytes(1536 * 1024 * 1024);
Config.setDelayRenderTimeoutInMilliseconds(120_000);
Config.setChromiumOpenGlRenderer("swangle");
Config.setOverwriteOutput(true);
