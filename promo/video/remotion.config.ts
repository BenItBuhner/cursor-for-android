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
// The takes are 2520 and 2880 px wide: a frame of each held at once, with room for the next.
Config.setOffthreadVideoCacheSizeInBytes(1024 * 1024 * 1024);
Config.setDelayRenderTimeoutInMilliseconds(120_000);
// Without a GPU, every other renderer falls back to Chrome's software compositor, which misplaces and clips layers
// turned about two axes at once; SwiftShader through ANGLE composites the stage's 3D as a GPU would.
Config.setChromiumOpenGlRenderer("swangle");
Config.setOverwriteOutput(true);
