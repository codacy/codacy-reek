#!/usr/bin/env ruby
# frozen_string_literal: true

# Generates Codacy documentation for Reek:
#   docs/patterns.json
#   docs/description/description.json
#   docs/description/<SmellType>.md
#
# Smell detectors and their default parameters come from the installed reek gem
# (Reek::DetectorRepository). Markdown docs are not shipped in the gem, so they are
# downloaded from the Reek GitHub repository for the matching version tag.
#
# Hand-tuned values (level, category, subcategory, enabled, titles, descriptions,
# parameter descriptions) already present in docs/ are preserved; only new smells
# get defaults.

require 'reek'
require 'json'
require 'net/http'
require 'uri'

module ReekDoc
  DEFAULT_LEVEL    = 'Warning'
  DEFAULT_CATEGORY = 'CodeStyle'
  TIME_TO_FIX      = 5
  # enabled: controlled by Codacy's pattern toggle.
  # overrides: internal nested hash (e.g. LongParameterList allows 5 params for initialize).
  #   It isn't a user-facing option, Codacy can't edit nested objects, and Reek applies it anyway.
  NON_PARAMETER_KEYS = %w[enabled overrides].freeze

  PARAMETER_DESCRIPTIONS = {
    'exclude' => 'Ignores any context whose full description matches any element of this array.'
  }.freeze

  class Generator
    def initialize(base_dir)
      @base_dir        = base_dir
      @docs_dir        = File.join(base_dir, 'docs')
      @description_dir = File.join(@docs_dir, 'description')
      @version         = Reek::Version::STRING
    end

    def run
      puts "Generating documentation for Reek #{@version}"
      existing_patterns     = load_existing_patterns
      existing_descriptions = load_existing_descriptions

      detectors = Reek::DetectorRepository.smell_types.sort_by(&:smell_type)
      puts "Smell detectors found: #{detectors.map(&:smell_type).join(', ')}"

      patterns     = detectors.map { |d| pattern(d, existing_patterns[d.smell_type]) }
      descriptions = detectors.map { |d| description(d, existing_descriptions[d.smell_type]) }

      write_json(File.join(@docs_dir, 'patterns.json'),
                 { name: 'reek', version: @version, patterns: patterns })
      write_json(File.join(@description_dir, 'description.json'), descriptions)
      detectors.each { |d| write_markdown(d.smell_type) }
    end

    private

    def parameters(detector)
      detector.default_config.reject { |key, _| NON_PARAMETER_KEYS.include?(key.to_s) }
    end

    def pattern(detector, existing)
      existing ||= {}
      params = parameters(detector).map { |key, value| { name: key.to_s, default: serialize(value) } }
      {
        patternId:   detector.smell_type,
        level:       existing['level'] || DEFAULT_LEVEL,
        category:    existing['category'] || DEFAULT_CATEGORY,
        subcategory: existing['subcategory'],
        scanType:    existing['scanType'],
        parameters:  params.empty? ? nil : params,
        enabled:     existing.key?('enabled') ? existing['enabled'] : detector.default_config.fetch('enabled', true)
      }.compact
    end

    # Reek's config format expresses regexes as "/source/" strings (e.g. reject: ["/^[a-z]$/"]).
    # Without this, JSON would emit Regexp#to_s ("(?-mix:^[a-z]$)"), which Reek treats as a literal.
    def serialize(value)
      case value
      when Regexp then "/#{value.source}/"
      when Array  then value.map { |v| serialize(v) }
      when Hash   then value.transform_values { |v| serialize(v) }
      else value
      end
    end

    def description(detector, existing)
      existing ||= {}
      fallback  = detector.smell_type.split(/(?=[A-Z])/).join(' ')
      existing_params = (existing['parameters'] || []).to_h { |p| [p['name'], p['description']] }
      params = parameters(detector).keys.map do |key|
        key = key.to_s
        { name: key, description: existing_params[key] || PARAMETER_DESCRIPTIONS[key] || key }
      end
      {
        patternId:   detector.smell_type,
        title:       existing['title'] || fallback,
        description: existing['description'] || fallback,
        timeToFix:   existing['timeToFix'] || TIME_TO_FIX,
        parameters:  params.empty? ? nil : params
      }.compact
    end

    def write_markdown(smell_type)
      path    = File.join(@description_dir, "#{smell_type}.md")
      content, ref = fetch_markdown(smell_type)
      if content
        File.write(path, absolutize_links(content, ref))
      elsif File.exist?(path)
        warn "Could not download docs for #{smell_type}; keeping existing #{path}"
      else
        warn "Could not download docs for #{smell_type}; writing placeholder"
        File.write(path, "# #{smell_type}\n\n[Source](https://github.com/troessner/reek/blob/master/docs/#{doc_file_name(smell_type)}.md)\n")
      end
    end

    # Reek's repo names doc files with hyphens: InstanceVariableAssumption -> Instance-Variable-Assumption.md
    def doc_file_name(smell_type)
      smell_type.split(/(?=[A-Z])/).join('-')
    end

    def fetch_markdown(smell_type)
      ["v#{@version}", 'master'].each do |ref|
        uri = URI("https://raw.githubusercontent.com/troessner/reek/#{ref}/docs/#{doc_file_name(smell_type)}.md")
        response = Net::HTTP.get_response(uri)
        return [response.body, ref] if response.is_a?(Net::HTTPSuccess)
      rescue StandardError => e
        warn "Error fetching #{uri}: #{e.message}"
      end
      nil
    end

    # Upstream docs link to siblings relatively, e.g. [Basic Smell Options](Basic-Smell-Options.md).
    # Those targets don't exist locally (and some aren't smells at all), so point them at GitHub.
    def absolutize_links(content, ref)
      base = "https://github.com/troessner/reek/blob/#{ref}/docs/"
      content.gsub(%r{\]\((?!https?://|#|/|mailto:)([^)\s]+)\)}) { "](#{base}#{Regexp.last_match(1)})" }
    end

    def load_existing_patterns
      path = File.join(@docs_dir, 'patterns.json')
      return {} unless File.exist?(path)

      JSON.parse(File.read(path)).fetch('patterns', []).to_h { |p| [p['patternId'], p] }
    end

    def load_existing_descriptions
      path = File.join(@description_dir, 'description.json')
      return {} unless File.exist?(path)

      JSON.parse(File.read(path)).to_h { |d| [d['patternId'], d] }
    end

    def write_json(path, data)
      File.write(path, "#{JSON.pretty_generate(data)}\n")
    end
  end
end

ReekDoc::Generator.new(ARGV[0] || '.').run
