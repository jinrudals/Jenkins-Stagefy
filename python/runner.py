import subprocess
import multiprocessing
from typing import Any
from yaml import safe_load

import argparse


class Env:
    values = dict()
    def __getattr__(self, __name: str) -> Any:
        return str(self.values.get(__name, "null"))
    def __setattr__(self, __name: str, __value: Any) -> None:
        self.values[__name] = __value

class LoopedStage(Exception):
    def __init__(self, current, parent) -> None:
        self.current = current
        self.parent = parent
    def __str__(self):
        return f"Looped Stage. From ({self.current}) by {self.parent}"
class NoSuchStage(Exception):
    def __init__(self, name, filename):
        self.name = name
        self.filename = filename
    def __str__(self):
        return f"NoSuchStage called {self.name} in {self.filename}"
class TooManyStageType(Exception):
    def __init__(self, name, filename, values):
        self.name = name
        self.filename = filename
        self.values = values
    def __str__(self) -> str:
        return f"Only one type should be defined. But {self.values} are defined. Check {self.name} from {self.filename}"
class TypeNotFound(Exception):
    def __init__(self, name, filename, tpe) -> None:
        self.name = name
        self.filename = filename
        self.tpe = tpe
    def __str__(self) -> str:
        return f"No matching stage as {self.tpe}. Check {self.name} from {self.filename}"
class Stage:
    env = Env()
    @classmethod
    def getData(cls, name, filename, parent = None, from_lazyed = False):
        # print(name, filename)
        if filename.strip() == "$HERE":
            filename = parent.key
        elif parent == None:
            pass
        elif filename != parent.key and not from_lazyed:
            return LazyStage(name, filename, parent, filename)
        mapped = {
            "steps" : StepStage,
            "parallels" : ParallelStage,
            "stages" : SequentialStage
        }
        with open(filename) as f:
            data = safe_load(f)
        sss = data.get(name)
        if not sss:
            raise NoSuchStage(name, filename)
        if len(sss.keys()) > 1:
            raise TooManyStageType(name, filename, sss.keys())
        tpe = list(sss.keys())[0]
        if tpe not in mapped:
            raise TypeNotFound(name, filename, tpe)
        return mapped[tpe](name, sss[tpe], parent, filename)
    @staticmethod
    def resolve_stage(string : str) -> tuple:
        x = string.split('from')
        if len(x) > 1:
            return (x[0].strip(), x[1].strip())
        else:
            return (x[0].strip(), "$HERE")
    def run(self):
        pass

    @classmethod
    def setEnvFromFile(cls, filename):
        with open(filename) as f:
            data = safe_load(f).get('env', {})
            cls.setEnvFromDict(data)

    @classmethod
    def setEnvFromDict(cls, data):
        for key, value in data.items():
            cls.setEnv(key, value)

    @classmethod
    def setEnv(cls, name, value):
        cls.env.values[name] = value

    def check_circular_loop(self, other = None):
        if not self.parent:
            return
        else:
            if self.parent == other:
                raise LoopedStage(other, self)
            else:
                return self.parent.check_circular_loop(other)
    def __eq__(self, __value: object) -> bool:
        return str(self) == str(__value)
    @classmethod
    def reset(cls):
        cls.env = Env()
class LazyStage(Stage):
    def __init__(self, name, filename, parent, key):
        # print(f"I am Lazy with {name} from {filename}")
        self.name = name
        self.filename = filename
        self.parent = parent
        self.key = key

    def run(self):
        dd = Stage.getData(self.name, self.filename, parent=self.parent, from_lazyed=True)
        return dd.run()
    def __repr__(self):
        return f"Lazy({self.name},{self.filename})"
class StepStage(Stage):
    def __init__(self, name, steps, parent, key):
        self.name = name
        self.parent = parent
        self.key = key
        self.steps = steps
    def run(self):
        env = self.env
        for step in self.steps:
            if 'sh' in step:
                command = eval(f'f"{step.get("sh")}"').replace('$','')
                print(f"Run command {command} at {self.name}")
                subprocess.run(command, shell=True)
    def __repr__(self):
        return f"Step({self.name},{self.key})"

class ParallelStage(Stage):
    def __init__(self, name, stages, parent, key) -> None:
        self.name = name
        self.key = key
        self.parent = parent
        self.check_circular_loop(self)
        stages = map(Stage.resolve_stage, stages)
        self.maps = list(map(lambda x : Stage.getData(x[0], x[1], self), stages))
        pass
    def run(self):
        lst = []
        for each in self.maps:
            p = multiprocessing.Process(target=self.wrapper, args=(each, ))
            lst.append(p)
        for each in lst:
            each.start()
        for each in lst:
            each.join()
    def __repr__(self):
        return f"Parallel({self.name},{self.key})"


    @staticmethod
    def wrapper(clz):
        clz.run()
class SequentialStage(Stage):
    def __init__(self, name, stages, parent, key) -> None:
        self.name = name
        self.key = key
        self.parent = parent
        self.check_circular_loop(self)
        stages = map(Stage.resolve_stage, stages)
        self.maps = list(map(lambda x : Stage.getData(x[0], x[1], self), stages))
    def run(self):
        for each in self.maps:
            each.run()
    def __repr__(self):
        return f"Sequential({self.name},{self.key})"


def parser():
    parse = argparse.ArgumentParser(description="Run Jenkins YAML")
    parse.add_argument('--path', required=True)
    parse.add_argument('--start', required=True)
    parse.add_argument('--env', nargs='*')
    return parse

if __name__ == "__main__":
    args = parser().parse_args()
    if args.env:
        mapped = dict(map(lambda x : (x.split('=')[0], x.split('=')[1]), args.env))
        Stage.setEnvFromDict(mapped)
    Stage.setEnvFromFile(args.path)

    stage = Stage.getData(args.start, args.path)
    stage.run()
