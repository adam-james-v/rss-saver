# RSS-saver
A simple Clojure (Babashka) script to save world@hey.com blog posts locally.

I use hey.com's blog feature to write blog posts to help me clarify and improve my own thinking about life, design, and programming. It's a cool feature of a nice email service, but I worry that I may not be able to retrieve my posts in the event of service shutdown, or if I move on to another email provider in the future.

This script is meant to be run automatically every night (or so) via a crontab entry. It downloads the feed.atom xml file from the provided URL, checks for any changes from the previous download, and saves new entries.

## Usage
To run this script once, you must supply the feed url with `-u` or `--url`. The provided URL must point to the rss feed XML file directly. For example, my URL is [](https://world.hey.com/adam.james/feed.atom).

```sh
bb rss-saver.clj -u YOUR_URL
```

The above command will download the XML file, parse and save to .edn files each post into the ./posts folder. You can change some options with the following:

```sh
Usage:
 -h, --help
 -u, --url URL                The URL of the RSS feed you want to save.
 -d, --dir DIR        ./posts The directory where articles will be saved.
 -f, --format FORMAT  edn     The format of saved articles. Either 'html' or
                              'edn' for a Clojure Map with the post saved as
                              Hiccup style syntax. Defaults to edn if unspecified.
 -c, --clear                  Clear the cached copy of the previous feed.
 -s, --silent                 Silence the script's output.
```

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

## Status
The script is complete and working as intended. A few minor things remain to tweak:

 - add some docstrings to the more complicated functions
 - add a few tests for node transforms.
