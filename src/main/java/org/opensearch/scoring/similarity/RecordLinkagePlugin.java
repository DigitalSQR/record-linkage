/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */
package org.opensearch.scoring.similarity;

import org.opensearch.plugins.Plugin;
import org.apache.lucene.index.LeafReaderContext;
import org.opensearch.common.settings.Settings;
import org.opensearch.plugins.Plugin;
import org.opensearch.plugins.ScriptPlugin;
import org.opensearch.script.ScoreScript;
import org.opensearch.script.ScoreScript.LeafFactory;
import org.opensearch.script.ScriptContext;
import org.opensearch.script.ScriptEngine;
import org.opensearch.script.ScriptFactory;
import org.opensearch.search.lookup.SearchLookup;
import org.opensearch.scoring.similarity.MatcherService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecordLinkagePlugin extends Plugin implements ScriptPlugin {
        /**
     * Returns a {@link ScriptEngine} instance.
     *
     * @param settings Node settings
     * @param contexts The contexts that {@link ScriptEngine#compile(String, String, ScriptContext, Map)} may be called with
     */
    @Override
    public ScriptEngine getScriptEngine(Settings settings, Collection<ScriptContext<?>> contexts) {
        return new SimilarityScriptEngine();
    }

    /**
     * Custom {@link ScriptEngine} implementation for string similarity.
     */
    private static class SimilarityScriptEngine implements ScriptEngine {

        /**
         * The language name used in the script APIs to refer to this scripting backend.
         */
        @Override
        public String getType() {
            return "similarity_scripts";
        }

        /**
         * Compiles the script.
         *
         * @param scriptName   the name of the script. {@code null} if it is anonymous (inline). For a stored script, its the
         *                     identifier.
         * @param scriptSource actual source of the script
         * @param context      the context this script will be used for
         * @param params       compile-time parameters (such as flags to the compiler)
         *
         * @return A compiled script of the FactoryType from {@link ScriptContext}
         */
        @Override
        public <FactoryType> FactoryType compile(
                String scriptName,
                String scriptSource,
                ScriptContext<FactoryType> context,
                Map<String, String> params
        ) {
            if (context.equals(ScoreScript.CONTEXT) == false) {
                throw new IllegalArgumentException(getType() + " scripts cannot be used for context [" + context.name + "]");
            }
            if ("string_similarity".equals(scriptSource)) {
                ScoreScript.Factory factory = new SimilarityFactory();
                return context.factoryClazz.cast(factory);
            }
            throw new IllegalArgumentException("Unknown script name " + scriptSource);
        }

        /**
         * Script {@link ScriptContext}s supported by this engine.
         */
        @Override
        public Set<ScriptContext<?>> getSupportedContexts() {
            return Collections.singleton(ScoreScript.CONTEXT);
        }

    }

    /**
     * A factory to construct an instance of {@link SimilarityLeafFactory}.
     */
    private static class SimilarityFactory implements ScoreScript.Factory, ScriptFactory {

        /**
         * @return a new instance of {@link SimilarityLeafFactory}.
         */
        @Override
        public LeafFactory newFactory(Map<String, Object> params, SearchLookup lookup) {
            return new SimilarityLeafFactory(params, lookup);
        }

    }

    /**
     * A factory to construct new {@link ScoreScript} instances.
     */
    private static class SimilarityLeafFactory implements LeafFactory {

        private final MatcherService matcherService = new MatcherService();
        private Map<String, Object> params;
        private List<MatcherModel> matchers;
        private SearchLookup lookup;

        SimilarityLeafFactory(Map<String, Object> params, SearchLookup lookup) {
            if (params.containsKey("matchers") == false) {
                throw new IllegalArgumentException("Missing parameter [matchers]");
            }
            if (params.containsKey("score_mode") == false) {
                throw new IllegalArgumentException("Missing parameter [score_mode]");
            }
            String score_mode = String.valueOf(params.get("score_mode"));
            ArrayList<String> validMethods = new ArrayList<String>( Arrays.asList( "fellegi-sunter", "bayes", "multiply", "sum" ) );
            if ( !validMethods.contains( score_mode ) ) {
                throw new IllegalArgumentException(
                        "Invalid parameter. Method can only be: fellegi-sunter, bayes, multiply or sum. Method is " 
                        + score_mode );
            }
            if (score_mode.equals("fellegi-sunter") && params.containsKey("base_score") == false) {
                throw new IllegalArgumentException("Missing parameter [base_score] for fellegi-sunter (because results can't be negative)");
            }
            this.params = params;
            this.matchers = MatcherModelParser.parseMatcherModels(params);
            this.lookup = lookup;
        }

        @Override
        public boolean needs_score() {
            return false;
        }

        @Override
        public ScoreScript newInstance(LeafReaderContext ctx) throws IOException {

            String score_mode = String.valueOf(params.get("score_mode"));
            if ( score_mode.equals( "fellegi-sunter" ) ) {

                double base_score = Double.parseDouble( String.valueOf( params.get("base_score") ) );
                return new ScoreScript(params, lookup, ctx) {
                    @Override
                    public double execute(ExplanationHolder explanation) {
                        double totalScore = base_score;
                        for (MatcherModel matcherModel : matchers) {
                            String value = String.valueOf(lookup.source().get(matcherModel.fieldName));
                            String nullHandling = "";
                            if ( value.equals("") && matcherModel.value.equals("") ) {
                              if ( matcherModel.nullHandlingBoth.equals("") ) {
                                nullHandling = matcherModel.nullHandling;
                              } else {
                                nullHandling = matcherModel.nullHandlingBoth;
                              }
                            } else if ( value.equals("") || matcherModel.value.equals("") ) {
                              nullHandling = matcherModel.nullHandling;
                            }
                            if ( nullHandling.equals("conservative") ) {
                              totalScore += matcherModel.unmatch;
                            } else if ( nullHandling.equals("greedy") ) {
                              totalScore += matcherModel.match;
                            } else if ( nullHandling.equals("moderate") ) {
                              // No change to score if moderate
                              //totalScore += 0.0;
                            } else {
                                double score = matcherService.matchScore(matcherModel.matcherName, matcherModel.value, value);
                                if ( matcherService.isDistance(matcherModel.matcherName) 
                                    ? score <= matcherModel.threshold : score >= matcherModel.threshold ) {
                                    totalScore += matcherModel.match;
                                } else {
                                    totalScore += matcherModel.unmatch;
                                }
                            }
                        }
                        return totalScore;
                    }
                };

            } else if ( score_mode.equals( "bayes" ) ) { 
                double NOT_SCORED = 2;
                return new ScoreScript(params, lookup, ctx) {

                    @Override
                    public double execute(ExplanationHolder explanation) {
                        double totalScore = NOT_SCORED;
                        for (MatcherModel matcherModel : matchers) {
                            String value = String.valueOf(lookup.source().get(matcherModel.fieldName));
                            boolean noScore = false;
                            String nullHandling = "";

                            if ( value.equals("") && matcherModel.value.equals("") ) {
                              if ( matcherModel.nullHandlingBoth.equals("") ) {
                                nullHandling = matcherModel.nullHandling;
                              } else {
                                nullHandling = matcherModel.nullHandlingBoth;
                              }
                            } else if ( value.equals("") || matcherModel.value.equals("") ) {
                              nullHandling = matcherModel.nullHandling;
                            }

                            double score;
                            if ( nullHandling.equals("conservative") ) {
                                score = matcherModel.low;
                            } else if ( nullHandling.equals("greedy") ) {
                                score = matcherModel.high;
                            } else if ( nullHandling.equals("moderate") ) {
                                // No change to score if moderate
                                //totalScore += 0.0;
                                noScore = true;
                                score = 0.0;
                            } else {
                                score = matcherService.matchScore(matcherModel.matcherName, matcherModel.value, value);
                                if (score > matcherModel.high) {
                                    score = matcherModel.high;
                                }
                                if (score < matcherModel.low) {
                                    score = matcherModel.low;
                                }
                            }
                            if ( !noScore ) {
                                totalScore = totalScore == NOT_SCORED ? score : combineScores(totalScore, score);
                            }
                        }
                        return totalScore;
                    }

                    /**
                    * From: https://github.com/larsga/Duke/blob/master/duke-core/src/main/java/no/priv/garshol/duke/utils/Utils.java
                    * Combines two probabilities using Bayes' theorem. This is the
                    * approach known as "naive Bayes", very well explained here:
                    * http://www.paulgraham.com/naivebayes.html
                    */
                    public double combineScores(double score1, double score2) {
                        return (score1 * score2) / ((score1 * score2) + ((1.0 - score1) * (1.0 - score2)));
                    }
    
                };

             } else if ( score_mode.equals( "multiply" ) ) {

                return new ScoreScript(params, lookup, ctx) {

                    @Override
                    public double execute(ExplanationHolder explanation) {
                        double totalScore = 1.0;
                        for (MatcherModel matcherModel : matchers) {
                            String value = String.valueOf(lookup.source().get(matcherModel.fieldName));
                            boolean noScore = false;
                            String nullHandling = "";

                            if ( value.equals("") && matcherModel.value.equals("") ) {
                              if ( matcherModel.nullHandlingBoth.equals("") ) {
                                nullHandling = matcherModel.nullHandling;
                              } else {
                                nullHandling = matcherModel.nullHandlingBoth;
                              }
                            } else if ( value.equals("") || matcherModel.value.equals("") ) {
                              nullHandling = matcherModel.nullHandling;
                            }

                            double score;
                            if ( nullHandling.equals("conservative") ) {
                                score = 0.0;
                            } else if ( nullHandling.equals("greedy") ) {
                                // This result will be a bit odd without a threshold set
                                score = 1.0;
                            } else if ( nullHandling.equals("moderate") ) {
                                // No change to score if moderate
                                //totalScore += 0.0;
                                noScore = true;
                                score = 0.0;
                            } else {
                                score = matcherService.matchScore(matcherModel.matcherName, matcherModel.value, value);
                                if ( matcherModel.threshold != 0.0 ) {
                                    if ( matcherService.isDistance(matcherModel.matcherName) 
                                        ? score <= matcherModel.threshold : score >= matcherModel.threshold ) {
                                        score = 1.0;
                                    } else {
                                        score = 0.0;
                                    }
                                }
                            }
                            if ( !noScore ) {
                                totalScore = totalScore * score * matcherModel.weight;
                            }
                        }
                        return totalScore;
                    }

                };

             } else { // default to sum if nothing is set

                return new ScoreScript(params, lookup, ctx) {

                    @Override
                    public double execute(ExplanationHolder explanation) {
                        double totalScore = 0.0;
                        for (MatcherModel matcherModel : matchers) {
                            String value = String.valueOf(lookup.source().get(matcherModel.fieldName));
                            String nullHandling = "";

                            if ( value.equals("") && matcherModel.value.equals("") ) {
                              if ( matcherModel.nullHandlingBoth.equals("") ) {
                                nullHandling = matcherModel.nullHandling;
                              } else {
                                nullHandling = matcherModel.nullHandlingBoth;
                              }
                            } else if ( value.equals("") || matcherModel.value.equals("") ) {
                              nullHandling = matcherModel.nullHandling;
                            }

                            double score;
                            if ( nullHandling.equals("conservative") ) {
                                score = 0.0;
                            } else if ( nullHandling.equals("greedy") ) {
                                // This result will be a bit odd without a threshold set
                                score = 1.0;
                            } else if ( nullHandling.equals("moderate") ) {
                                // Same as conservative when doing sum
                                //totalScore += 0.0;
                                score = 0.0;
                            } else {
                                score = matcherService.matchScore(matcherModel.matcherName, matcherModel.value, value);
                                if ( matcherModel.threshold != 0.0 ) {
                                    if ( matcherService.isDistance(matcherModel.matcherName) 
                                        ? score <= matcherModel.threshold : score >= matcherModel.threshold ) {
                                        score = 1.0;
                                    } else {
                                        score = 0.0;
                                    }
                                }
                            }
                            totalScore += score * matcherModel.weight;
                        }
                        return totalScore;
                    }

                };

             }
        }

    }

    /**
     * Encapsulates a field with its value, preferred matcher and the high and low values to be used for scoring.
     */
    private static class MatcherModel {

        /**
         * The name of the field to be matched.
         */
        private String fieldName;

        /**
         * The value of the field to be matched.
         */
        private String value;

        /**
         * The name of the matcher to use for matching.
         */
        private String matcherName;

        /**
         * The score to assign a perfect match. Should be high, non-zero and between 0 and 1.
         */
        private double high;

        /**
         * The score to assign a perfect match. Should be low, non-zero and between 0 and 1.
         */
        private double low;

        /**
         * The match weight for Fellegi-Sunter linkage. Based on the mValue and uValue for the field.
         */
        private double match;

        /**
         * The unmatch weight for Fellegi-Sunter linkage. Based on the mValue and uValue for the field.
         */
        private double unmatch;

        /**
         * The threshold to determine a match or not based on the string distance or similarity.
         */
        private double threshold;

        /**
         * The weight for the field when using sum or multiple score_modes.
         */
        private double weight;

        /**
         * The name of the matcher to use for matching.
         */
        private String nullHandling;

        /**
         * The name of the matcher to use for matching.
         */
        private String nullHandlingBoth;

        /**
         * Constructs a new instance of a MatcherModel.
         */
        MatcherModel(String fieldName, Object value, String matcherName, double high, double low, 
                double mValue, double uValue, double threshold, double weight, 
                String nullHandling, String nullHandlingBoth) {
            this.fieldName = fieldName;
            this.value = String.valueOf(value);
            this.matcherName = matcherName;
            this.high = high;
            this.low = low;
            this.match = java.lang.Math.log10( mValue / uValue );
            this.unmatch = java.lang.Math.log10( (1 - mValue) / (1 - uValue) );
            this.threshold = threshold;
            this.weight = weight;
            this.nullHandling = nullHandling;
            this.nullHandlingBoth = nullHandlingBoth;
        }

    }

    /**
     * Converts each matcher entry from the script to a {@link MatcherModel}.
     */
    private static class MatcherModelParser {

        private static String FIELD = "field";
        private static String VALUE = "value";
        private static String MATCHER = "matcher";
        /* For Bayes score_mode */
        private static String HIGH = "high";
        private static String LOW = "low";
        /* For Fellegi-Sunter score_mode */
        private static String MVALUE = "m_value";
        private static String UVALUE = "u_value";
        private static String THRESHOLD = "threshold";
        private static String WEIGHT = "weight";
        /* For null value handling */
        private static String NULL_HANDLING = "null_handling";
        private static String NULL_HANDLING_BOTH = "null_handling_both";

        @SuppressWarnings("unchecked")
        public static List<MatcherModel> parseMatcherModels(Map<String, Object> params) {
            final String score_mode = String.valueOf(params.get("score_mode"));
            List<MatcherModel> matcherModels = new ArrayList<>();
            List<Map<String, Object>> script = (List<Map<String, Object>>) params.get("matchers");
            script.forEach(entry -> {
                checkMatcherConfiguration(score_mode, entry);
                String fieldName = String.valueOf(entry.get(FIELD));
                String value = String.valueOf(entry.get(VALUE));
                String matcherName = String.valueOf(entry.get(MATCHER));
                String nullHandling = "off";
                String nullHandlingBoth = "";
                if ( entry.containsKey(NULL_HANDLING) ) {
                    nullHandling = String.valueOf(entry.get(NULL_HANDLING));
                    if ( entry.containsKey(NULL_HANDLING_BOTH) ) {
                        nullHandlingBoth = String.valueOf(entry.get(NULL_HANDLING_BOTH));
                    }
                }
                double high, low, mValue, uValue, threshold;
                double weight = 1.0;
                if ( score_mode.equals("fellegi-sunter" ) ) {
                    mValue = Double.parseDouble( String.valueOf( entry.get(MVALUE) ) );
                    uValue = Double.parseDouble( String.valueOf( entry.get(UVALUE) ) );
                    threshold = Double.parseDouble( String.valueOf( entry.get(THRESHOLD) ) );
                    high = low = 0.0;
                } else if ( score_mode.equals("bayes") ) { 
                    high = Double.parseDouble( String.valueOf( entry.get(HIGH) ) );
                    low = Double.parseDouble( String.valueOf( entry.get(LOW) ) );
                    mValue = uValue = threshold = 0.0;
                } else { // multiply and sum have the weight option
                    high = low = mValue = uValue = threshold = 0.0;
                    if ( entry.containsKey(WEIGHT) ) {
                        weight = Double.parseDouble( String.valueOf( entry.get(WEIGHT) ) );
                    }
                    if ( entry.containsKey(THRESHOLD) ) {
                        threshold = Double.parseDouble( String.valueOf( entry.get(THRESHOLD) ) );
                    }
                }
                matcherModels.add(new MatcherModel(fieldName, value, matcherName, high, low, mValue, uValue, 
                      threshold, weight, nullHandling, nullHandlingBoth));
            });
            return matcherModels;
        }

        private static void checkMatcherConfiguration(String score_mode, Map<String, Object> entry) {
            if (!entry.containsKey(FIELD)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + FIELD + "] property.");
            }
            if (!entry.containsKey(VALUE)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + VALUE + "] property.");
            }
            if (!entry.containsKey(MATCHER)) {
                throw new IllegalArgumentException("Invalid matcher configuration. Missing: [" + MATCHER + "] property.");
            }
            if ( score_mode.equals( "fellegi-sunter" ) ) {
                if (!entry.containsKey(THRESHOLD)) {
                    throw new IllegalArgumentException("Invalid matcher configuration for fellegi-sunter. Missing: [" 
                            + THRESHOLD + "] property.");
                }
                if (!entry.containsKey(MVALUE)) {
                    throw new IllegalArgumentException("Invalid matcher configuration for fellegi-sunter. Missing: [" 
                            + MVALUE + "] property.");
                }
                if (!entry.containsKey(UVALUE)) {
                    throw new IllegalArgumentException("Invalid matcher configuration for fellegi-sunter. Missing: [" 
                            + UVALUE + "] property.");
                }
            } else if ( score_mode.equals( "bayes" ) ) { 
                if (!entry.containsKey(HIGH)) {
                    throw new IllegalArgumentException("Invalid matcher configuration for bayes. Missing: [" 
                            + HIGH + "] property.");
                }
                if (!entry.containsKey(LOW)) {
                    throw new IllegalArgumentException("Invalid matcher configuration for bayes. Missing: [" 
                            + LOW + "] property.");
                }
            }
        }

    }
}
