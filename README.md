# Jenkins Stagefy

Jenkins can only run one Jenkinsfile per Item(Job). This leads to multiple jobs, and integrate with GitLab per repository.

Moreover, some repositories need to run the same `stage`. With current Jenkins flow, a job needs to build child jobs, to achieve this goal. Otherwise, we need to copy-and-paste the stage.

In addition, the one who writes Jenkinsfile should always understand the whole stage flows for all repositories (or items / jobs). In contrast, the project manager may not understand Jenkins itself (even though it is quite easy to learn).

One last thing: we cannot run the Jenkins script locally.

## Let's Write Stage understantable for all users!

Jenkins `Stage` can be categorized in three types.

- Stage that runs other stages sequentially
- Stage that runs other stages parallely
- Stage that runs shell script

The last category is quite easy for all engineers. However, for the above two, users need to understande Jenkins. But what if we describe as below?

```yaml
StageA:
  stages:
    - A
    - B
    - C

StageB:
  parallels:
    - D
    - E
    - F

StageC:
  steps:
    - sh : "command"
```

Isn't it quite straight forward for understanding?
`StageA` runs child stages sequentially, whereas `StageB` runs them parallely. `StageC` doesn't have child stages, runs it's own command list.

Moreover, when make shared stage as child, we can call as below:
```yaml
StageD:
  stages:
    - {name : C, file: "path/to/other/yaml"}
```

The last thing: We can parse this yaml data into python or other script to run the script locally.

# How it works
```groovy
def getData(name, currentfile, loadedStage = [:]){

}
```
We parse the start stage name and file. If the start stage is `step` type, it returns the stage itself. Otherwise, it find child stage information.
