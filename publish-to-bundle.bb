#!/usr/bin/env bb

(require '[content-workflow.publish :as publish])

(apply publish/-main *command-line-args*)
