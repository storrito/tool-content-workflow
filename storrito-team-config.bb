#!/usr/bin/env bb

(ns storrito-team-config
  (:require [babashka.fs :as fs]))

(fs/copy-tree "storrito-config" ".")
