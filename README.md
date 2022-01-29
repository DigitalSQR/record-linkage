# record-linkage
An OpenSearch plugin for scoring documents based on string similarity.  This is based on the 
[string-similarity](https://github.com/intrahealth/similarity-scoring) plugin for ElasticSearch.

## Details
The OpenSearch plugin  relies on the https://github.com/tdebatty/java-string-similarity library. 
The library is fully open source and publicly hosted on Github under the MIT license. 

The plugin currently supports these algorithms.  See the above library for more details.

* Normalized algorithms return results between 0.0 and 1.0 and usually allow both distance and similarity scores.
* Distance algorithms define the distance between strings so 0 is a perfect match.
* Similarity algorithms define the similarity of strings so 0 means the strings are completely different.

Matcher Parameter for Query| Algorithm | Type | Normalized?
---|---|---|---
cosine-similarity | Cosine | similarity | yes
dice-similarity | Sorensen-Dice | similarity | yes
jaccard-similarity | Jaccard | similarity | yes
jaro-winkler-similarity | Jaro-Winkler | similarity | yes
normalized-lcs-similarity | Normalized Longest Common Subsequence | similarity | yes
normalized-levenshtein-similarity | Normalized Levenshtein | similarity | yes
cosine-distance | Cosine | distance | yes
damerau-levenshtein | Damerau-Levenshtein | distance | no
dice-distance | Sorensen-Dice | distance | yes
jaccard-distance | Jaccard | distance | yes
jaro-winkler-distance | Jaro-Winkler | distance | yes
levenshtein | Levenshtein | distance | no
longest-common-subsequence | Longest Common Subsequence | distance | no
metric-lcs | Metric Longest Common Subsequence | distance | yes
ngram | N-Gram | distance | yes
normalized-lcs-distance | Normalized Longest Common Subsequence | distance | yes
normalized-levenshtein-distance | Normalized Levenshtein | distance | yes
optimal-string-alignment | Optimal String Alignment | distance | no
qgram | Q-Gram | distance | no
ratcliff-obershelp | Ratcliff-Obershelp | similarity | yes

## Building
The plugin can be built with Java 14 with the following command:

```bash
./gradlew build
```

## Installation
The plugin installation may be installed using the standard Elasticsearch installation
procedure.

```bash
opensearch-plugin install file://path-to-plugin-zip-file
systemctl restart opensearch
```

Replace path-to-plugin-zip-file with the correct path to the plugin installation zip
file.

## Docker Image

You can create a docker image of OpenSearch with the plugin loaded with these commands:

```bash
docker build --tag=opensearch-record-linkage .
docker run -p 9200:9200 -p 9600:9600 -v /usr/share/opensearch/data opensearch-record-linkage
```


## Querying
Just like the old plugin, the new plugin may be tested by submitting to an OpenSearch
server a JSON formatted query of the following form.
```bash
curl -X POST "localhost:9200/patients/_search?pretty=true" -H
'Content-Type: application/json' -d'{
  "query": {
    "function_score": {
      "query": {
        "match_all": {}
      },
      "functions": [
        {
          "script_score": {
            "script": {
              "source": "string_similarity",
              "lang" : "similarity_scripts",
              "params": {
                "score_mode": "bayes",
                "matchers": [{
                  "field": "given",
                  "value": "Alis",
                  "matcher": "jaro-winkler-similarity",
                  "high": 0.9,
                  "low": 0.1
                },{
                  "field": "family",
                  "value": "Brock",
                  "matcher": "jaro-winkler-similarity",
                  "high": 0.9,
                  "low": 0.1
                }]
              }
            }
          }
        }
      ]
    }
  }
}'
```
To combine scores using Fellegi-Sunter you need to have m and u values for the fields as well
as a baseScore parameter because OpenSearch doesn't allow negative scores.  The baseScore
should be the minimum for the min_score on the query but it should be adjusted higher based
on your selection criteria.  For similarity comparisons, the score must be higher than the 
threshold given.  For distance comparisons, the score must be lower than the threshold given.
```bash
curl -X POST "localhost:9200/patients/_search?pretty=true" -H
'Content-Type: application/json' -d'{
  "query": {
    "function_score": {
      "query": {
        "match": { "gender": "female" } // blocks can go here
      },
      "functions": [
        {
          "script_score": {
            "script": {
              "source": "string_similarity",
              "lang" : "similarity_scripts",
              "params": {
                "score_mode": "fellegi-sunter",
                "base_score": 100.0,
                "matchers": [{
                  "field": "given",
                  "value": "Alis",
                  "matcher": "jaro-winkler-similarity",
                  "threshold": 0.9,
                  "m_value": 0.95736,
                  "u_value": 0.0003415
                },{
                  "field": "family",
                  "value": "Brock",
                  "matcher": "jaro-winkler-similarity",
                  "threshold": 0.9,
                  "m_value": 0.92873,
                  "u_value": 0.0008731
                }]
              }
            }
          }
        }
      ],
      "min_score": 100, // based on the base_score above so can be higher to limit results
      "boost_mode": "replace" // required so blocks don't affect the score
    }
  }
}'
```

If you want to use a deterministic scoring method, you can set the score_mode to sum or
multiply depending on how you want to combine the scores for multiple fields.  You can
also assign a weight for individual fields which will be multiplied with the returned score
for the matcher.

```bash
curl -X POST "localhost:9200/patients/_search?pretty=true" -H
'Content-Type: application/json' -d'{
  "query": {
    "function_score": {
      "query": { "match_all": {} },
      "functions": [
        {
          "script_score": {
            "script": {
              "source": "string_similarity",
              "lang" : "similarity_scripts",
              "params": {
                "score_mode": "sum",
                "matchers": [{
                  "field": "given",
                  "value": "Alis",
                  "matcher": "jaro-winkler-similarity"
                },{
                  "field": "family",
                  "value": "Brock",
                  "matcher": "damerau-levenshtein",
                  "threshold": 2.0,
                  "weight": 2.0
                }]
              }
            }
          }
        }
      ],
      "min_score": 1, // based on the base_score above so can be higher to limit results
      "boost_mode": "replace" // required so blocks don't affect the score
    }
  }
}'
```


The matchers key contains an array of all fields to be searched, configured with the
appropriate field name, value, algorithm, score_mode and additional parameters based on the score_mode.

Parameter | Description
---|---
field | The field to be searched e.g. "given".
value | The search term e.g. "Alis".
matcher | The algorithm to use for matching e.g. "jaro-winkler-similarity".
score_mode | How to combine scores for multiple matchers/fields.  The options are:  fellegi-sunter, bayes, multiply, or sum.
high | The score to be assigned to a string that matches the search term perfectly.  Applies to the bayes score_mode.
low | The score to be assigned to a string that does not match the search term at all.  Applies to the bayes score_mode.
threshold | A double value threshold for the field being a matched for the fellegi-sunter, multiply, or sum score_mode. When used with multiply or sum the score returned will be 1 or 0 if it met the treshold or not.  You can use weight to adjust this if necessary.  This is so you can use distance algorithms when a high returned value is less of a match.  Distance algorithms must be <= the threshold and similarity must be >= the threshold.
m_value | The *m* value for the field for the fellegi-sunter score_mode.
u_value | The *u* value for the field for the fellegi-sunter score_mode.
weight | A double value that will be multiplied with the returned score for the matcher when using score_mode of sum or multiply.  The default is 1.0.  Between 0.0 and 1.0 will reduce the score and anyting above will increase the score.
