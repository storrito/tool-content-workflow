#!/usr/bin/env bb

(require '[content-workflow.server :as server])

(apply server/-main *command-line-args*)
