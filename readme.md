# RSS-saver
A simple Clojure (Babashka) script to save world@hey.com blog posts locally.

I use hey.com's blog feature to write blog posts to help me clarify and improve my own thinking about life, design, and programming. It's a cool feature to a nice service, but I worry that I may not be able to retrieve my posts in the event of service shutdown, or if I move on to another email provider in the future.

This script is meant to be run automatically every night (or so). It downloads the feed.atom xml file from the provided URL, checks for any changes from the previous download, and saves new entries.

## Status
Working 'skeleton'. I wrote this during one of my streams, and it works with my (hardcoded) blog RSS URL. I have to clean up the node transformation and open up the feature set slightly yet, including:

 - option to change the feed URL to other hey URLs. I am NOT worrying about other RSS feed formats at this time. Not sure if they are standardised anyway.
 - add a few output options: .txt, .md, .org, .html ?? Not entirely sure which yet.
 - make a proper CLI w/ help printout
 - make sure a GraalVM native-image build is possible
