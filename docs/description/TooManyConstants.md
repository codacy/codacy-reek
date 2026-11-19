## Introduction

_Too Many Constants_ is a case of [Large Class](https://github.com/troessner/reek/blob/v6.6.0/docs/Large-Class.md).

## Example

Given this configuration

```yaml
TooManyConstants:
  max_constants: 3
```

and this code:

```ruby
class Smelly
  CONST_1 = :dummy
  CONST_2 = :dummy
  CONST_3 = :dummy
  CONST_4 = :dummy
end
```

Reek would emit the following warning:

```
test.rb -- 1 warning:
  [1]:TooManyConstants: Smelly has 4 constants
```
## Configuration

Reek's _Too Many Constants_ detector offers the [Basic Smell Options](https://github.com/troessner/reek/blob/v6.6.0/docs/Basic-Smell-Options.md), plus:

| Option                   | Value   | Effect  |
| -------------------------|---------|---------|
| `max_constants` | integer | The maximum number of constants that are permitted. Defaults to 5 |
