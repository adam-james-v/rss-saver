```
 ______________
|[]            |
|  __________  |
|  |  RSS-  |  |
|  | saver  |  |
|  |________|  |
|   ________   |
|   [ [ ]  ]   |
\___[_[_]__]___|

```

# RSS-saver
A simple Clojure (Babashka) script to save world@hey.com blog posts locally.

I use hey.com's blog feature to write blog posts to help me clarify and improve my own thinking about life, design, and programming. It's a cool feature of a nice email service, but I worry that I may not be able to retrieve my posts in the event of service shutdown, or if I move on to another email provider in the future.

This script is meant to be run automatically every night (or so) via a crontab entry. It downloads the feed.atom xml file from the provided URL, checks for any changes from the previous download, and saves new entries.

You can read the [design doc](./rss-saver.org#design) for a complete understanding of this project.

You can check out an interactive blog post version of this project [here](https://adam-james-v.github.io/dev/rss-saver-web/).

## Installation
If you already have the latest Babashka, all you have to do is grab the script from this repo:

```sh
git clone https://github.com/adam-james-v/rss-saver.git
cd rss-saver
```

And install the script `rss-saver.clj` wherever you like to run scripts from.

```sh
cp rss-saver.clj /path/to/your/scripts/dir
```

## Usage
To run this script once, you must supply the feed url with `-u` or `--url`. The provided URL must point to the rss feed XML file directly. For example, my URL is [https://world.hey.com/adam.james/feed.atom](https://world.hey.com/adam.james/feed.atom).

**NOTE:** Your URL *must* point to the feed.xml file for this script to work.

```sh
bb rss-saver.clj -u YOUR_URL
```

The above command will download the XML file, parse and save to .edn files each post into the ./posts folder. You can change some options with the following:

```sh
Usage:
 -h, --help
 -u, --url URL                 The URL of the RSS feed you want to save.
 -d, --dir DIR        ./posts  The directory where articles will be saved.
 -f, --format FORMAT  edn      The format of saved articles. Either 'html' or
                               'edn' for a Clojure Map with the post saved as
                               Hiccup style syntax. Defaults to edn if unspecified.
 -c, --clear                   Clear the cached copy of the previous feed.
 -s, --silent                  Silence the script's output.
```

I use this script with an automation using crontab on macOS. My crontab entry:

```sh
0 12 * * * /usr/local/bin/bb /Users/adam/scripts/rss-saver/rss-saver.clj -u https://world.hey.com/adam.james/feed.atom -d /Users/adam/scripts/rss-saver/posts
```

Which runs the script once at noon every day. It's default save directory is ./posts, and cron runs the script from your home folder, so my articles are saved in `/Users/adam/scripts/rss-saver/posts`, but you can set the path to wherever you want using the `-d` or `--dir` options. I recommend using an absolute path to avoid confusion.

## Requirements
RSS-Saver requires [Babashka](https://github.com/babashka/babashka). While writing this script, I was using *version 0.6.0*. The script uses the following libraries, which are bundled with the latest Babashka:

 - clojure.string
 - clojure.data.xml
 - clojure.java.io
 - clojure.zip
 - clojure.java.shell
 - clojure.tools.cli
 - hiccup.core

If you want to run things automatically, you need some mechanism to automate running scripts. I am using crontab.

## Tests
Since I've written this project in a literate style with org-mode, I can use Noweb and named code blocks to 'tangle' two versions of the script: **rss-saver.clj** which contains the code and the CLI but no tests, and **rss-saver-tests.clj** which contains the code and tests but no CLI.

To run tests:

```sh
bb rss-saver-tests.clj
```

Tests use `clojure.test` and invoke all tests by calling `(t/run-tests)` at the bottom of the script.

## Status
The script is complete and working as intended. Bugs will be fixed if I encounter them or if someone posts an issue. This is intended to be a *very* simple script with a small and specific scope, so new features won't be implemented. This project is *done* (Yay!).
