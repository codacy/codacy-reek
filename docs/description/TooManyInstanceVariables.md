## Introduction

_Too Many Instance Variables_ is a case of [Large Class](https://github.com/troessner/reek/blob/v6.6.0/docs/Large-Class.md).

## Example

Given this configuration

```yaml
TooManyInstanceVariables:
  max_instance_variables: 3
```

and this code:

```ruby
class Smelly
  def initialize
    @arg_1 = :dummy
    @arg_2 = :dummy
    @arg_3 = :dummy
    @arg_4 = :dummy
  end
end
```

Reek would emit the following warning:

```
test.rb -- 5 warnings:
  [1]:TooManyInstanceVariables: Smelly has at least 4 instance variables
```
## Current Support in Reek

Reek only counts the instance variables you use explicitly like in the example above. Class macros like `attr_accessor` are disregarded.

## Configuration

Reek's _Too Many Instance Variables_ detector offers the [Basic Smell Options](https://github.com/troessner/reek/blob/v6.6.0/docs/Basic-Smell-Options.md), plus:

| Option                   | Value   | Effect  |
| -------------------------|---------|---------|
| `max_instance_variables` | integer | The maximum number of instance variables that are permitted. Defaults to 4 |
