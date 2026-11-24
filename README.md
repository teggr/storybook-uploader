# storybook-uploader

Download story books and other audio from YouTube and upload them to your
[Toniebox](https://tonies.com) creative Tonies.

It strings together:

* [yt-dlp](https://github.com/yt-dlp/yt-dlp) to download the audio from YouTube,
* [ffmpeg](https://ffmpeg.org) (used by yt-dlp) to convert it to mp3, and
* [toniebox-api](https://github.com/maximilianvoss/toniebox-api) to upload it as a new chapter to a creative Tonie.

## Prerequisites

* [JBang](https://www.jbang.dev) (it will fetch a JDK 17+ if needed)
* `yt-dlp` and `ffmpeg` on your `PATH`

## Usage

Provide your Tonie (my.tonies.com) credentials as environment variables:

```bash
export TONIE_USERNAME=you@example.com
export TONIE_PASSWORD=secret
```

List your creative Tonies and their chapters:

```bash
jbang TonieUpload.java --list
```

Upload the audio of a YouTube video to a creative Tonie (the chapter title defaults to the video title):

```bash
jbang TonieUpload.java --tonie "Bunny" "https://www.youtube.com/watch?v=..."
```

`--tonie` accepts the name (case-insensitive) or id of the creative Tonie and can be left out if you only have one.
Use `--title "My title"` to set a different chapter title, and `--help` for all options.

The script can also be run directly on Linux/macOS with `./TonieUpload.java ...`.
