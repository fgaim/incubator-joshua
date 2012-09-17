package joshua.decoder.ff.lm;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import joshua.corpus.Vocabulary;
import joshua.decoder.chart_parser.SourcePath;
import joshua.decoder.ff.FeatureVector;
import joshua.decoder.ff.StatefulFF;
import joshua.decoder.ff.state_maintenance.StateComputer;
import joshua.decoder.ff.state_maintenance.DPState;
import joshua.decoder.ff.state_maintenance.NgramDPState;
import joshua.decoder.ff.tm.Rule;
import joshua.decoder.hypergraph.HGNode;

/**
 * This class performs the following:
 * <ol>
 * <li>Gets the additional LM score due to combinations of small items into larger ones by using
 * rules
 * <li>Gets the LM state
 * <li>Gets the left-side LM state estimation score
 * </ol>
 * 
 * @author Matt Post <post@cs.jhu.edu>
 * @author Zhifei Li, <zhifei.work@gmail.com>
 */
public class LanguageModelFF extends StatefulFF {

  /** Logger for this class. */
  private static final Logger logger = Logger.getLogger(LanguageModelFF.class.getName());

  private final String START_SYM = "<s>";
  private final int START_SYM_ID;
  private final String STOP_SYM = "</s>";
  private final int STOP_SYM_ID;


  /*
   * These must be static (for now) for LMGrammar, but they shouldn't be! in case of multiple LM
   * features
   */
  static String BACKOFF_LEFT_LM_STATE_SYM = "<lzfbo>";
  static public int BACKOFF_LEFT_LM_STATE_SYM_ID;// used for equivalent state
  static String NULL_RIGHT_LM_STATE_SYM = "<lzfrnull>";
  static public int NULL_RIGHT_LM_STATE_SYM_ID;// used for equivalent state

  private final boolean addStartAndEndSymbol = true;

  /**
   * N-gram language model. We assume the language model is in ARPA format for equivalent state:
   * 
   * <ol>
   * <li>We assume it is a backoff lm, and high-order ngram implies low-order ngram; absense of
   * low-order ngram implies high-order ngram</li>
   * <li>For a ngram, existence of backoffweight => existence a probability Two ways of dealing with
   * low counts:
   * <ul>
   * <li>SRILM: don't multiply zeros in for unknown words</li>
   * <li>Pharaoh: cap at a minimum score exp(-10), including unknown words</li>
   * </ul>
   * </li>
   */
  private final NGramLanguageModel lmGrammar;

  /**
   * We always use this order of ngram, though the LMGrammar may provide higher order probability.
   */
  private final int ngramOrder;


  /**
   * We cache the weight of the feature since there is only one.
   */
  private float weight;


  // boolean add_boundary=false; //this is needed unless the text already has <s> and </s>

  /**
   * stateID is any integer exept -1
   **/
  public LanguageModelFF(FeatureVector weights, String featureName, NGramLanguageModel lm, StateComputer state) {
    super(weights, featureName, state);
    this.lmGrammar = lm;
    this.ngramOrder = lm.getOrder();
    this.START_SYM_ID = Vocabulary.id(START_SYM);
    this.STOP_SYM_ID = Vocabulary.id(STOP_SYM);

    LanguageModelFF.BACKOFF_LEFT_LM_STATE_SYM_ID = Vocabulary.id(BACKOFF_LEFT_LM_STATE_SYM);
    LanguageModelFF.NULL_RIGHT_LM_STATE_SYM_ID = Vocabulary.id(NULL_RIGHT_LM_STATE_SYM);

    if (! weights.containsKey(name))
      System.err.println("* WARNING: no weight found for LanguageModelFF '" + name + "'");
      
    this.weight = weights.get(name);
  }

  // public float reEstimateTransitionLogP(Rule rule, List<HGNode> antNodes, int spanStart,
  //     int spanEnd, SourcePath srcPath, int sentID) {
  //   return reEstimateTransition(rule.getEnglish(), antNodes);
  // }

  // public double transitionLogP(Rule rule, List<HGNode> antNodes, int spanStart, int spanEnd,
  //     SourcePath srcPath, int sentID) {
  /**
   * Computes the cost of the transition, which is the inner product of the feature value computed
   * along this edge times the feature weight.
   *
   * @return the transition cost
   */
  @Override
  public float computeCost(Rule rule, List<HGNode> tailNodes, int i, int j,
      SourcePath srcPath, int sentID) {
    return weight * computeTransition(rule.getEnglish(), tailNodes);
  }


  /**
   * Computes the features incurred along this edge.  Note that these features are unweighted costs
   * of the feature; they are the feature cost, not the model cost, or the inner product of them.
   */
  public FeatureVector computeFeatures(Rule rule, List<HGNode> tailNodes, int i, int j, 
    SourcePath sourcePath, int sentID) {
    FeatureVector transitionFeatures = null;
    if (rule != null)
      transitionFeatures = new FeatureVector(name, computeTransition(rule.getEnglish(), tailNodes));
    else
      transitionFeatures = new FeatureVector();
    
    return transitionFeatures;
  }

  /**
   * Returns the feature accumulated over the final, top-level, rule-less transition.
   * @param tailNode
   * @param i
   * @param j
   * @param sourcePath
   * @param sentID
   * @return
   */
  public FeatureVector computeFinalFeatures(HGNode tailNode, int i, int j, SourcePath sourcePath, int sentID) {
    return new FeatureVector(name, computeFinalTransitionLogP((NgramDPState) tailNode.getDPState(this.getStateComputer())));
  }
  
  // public double finalTransitionLogP(HGNode antNode, int spanStart, int spanEnd, SourcePath srcPath,
  //     int sentID) {
  public float computeFinalCost(HGNode tailNode, int i, int j, SourcePath sourcePath, int sentID) {
    return weight * computeFinalTransitionLogP((NgramDPState) tailNode.getDPState(this.getStateComputer()));
  }


  /**
   * will consider all the complete ngrams, and all the incomplete-ngrams that will have sth fit
   * into its left side.
   */
  public float estimateCost(Rule rule, int sentID) {
    return estimateRuleLogProb(rule.getEnglish());
  }

  public float estimateFutureCost(Rule rule, DPState currentState, int sentID) {
    return estimateFutureLogP(rule, currentState, sentID);
  }

  public float estimateFutureLogP(Rule rule, DPState curDPState, int sentID) {
    // TODO: do not consider <s> and </s>
    boolean addStart = false;
    boolean addEnd = false;

    return estimateStateLogProb((NgramDPState) curDPState, addStart, addEnd);
  }

  /**
   * Compute the cost of a rule application. The cost of applying a rule is computed by determining
   * the n-gram costs for all n-grams created by this rule application, and summing them. N-grams
   * are created when (a) terminal words in the rule string are followed by a nonterminal (b)
   * terminal words in the rule string are preceded by a nonterminal (c) we encounter adjacent
   * nonterminals. In all of these situations, the corresponding boundary words of the node in the
   * hypergraph represented by the nonterminal must be retrieved.
   */
  private float computeTransition(int[] enWords, List<HGNode> tailNodes) {

    List<Integer> currentNgram = new LinkedList<Integer>();
    float transitionLogP = 0.0f;

    for (int c = 0; c < enWords.length; c++) {
      int curID = enWords[c];

      if (Vocabulary.nt(curID)) {
        int index = -(curID + 1);

        NgramDPState state = (NgramDPState) tailNodes.get(index).getDPState(this.getStateComputer());
        List<Integer> leftContext = state.getLeftLMStateWords();
        List<Integer> rightContext = state.getRightLMStateWords();
        if (leftContext.size() != rightContext.size()) {
          throw new RuntimeException(
              "computeTransition: left and right contexts have unequal lengths");
        }

        // Left context.
        for (int i = 0; i < leftContext.size(); i++) {
          int t = leftContext.get(i);
          currentNgram.add(t);

          // Always calculate logP for <bo>: additional backoff weight
          if (t == BACKOFF_LEFT_LM_STATE_SYM_ID) {
            // Number of non-state words.
            int numAdditionalBackoffWeight = currentNgram.size() - (i + 1);
            // Compute additional backoff weight.
            transitionLogP +=
                this.lmGrammar.logProbOfBackoffState(currentNgram, currentNgram.size(),
                    numAdditionalBackoffWeight);

            if (currentNgram.size() == this.ngramOrder) {
              currentNgram.remove(0);
            }
          } else if (currentNgram.size() == this.ngramOrder) {
            // compute the current word probablity, and remove it
            float prob = (float)this.lmGrammar.ngramLogProbability(currentNgram, this.ngramOrder);
            transitionLogP += prob;
            currentNgram.remove(0);
          }

        }

        // Right context.
        int tSize = currentNgram.size();
        for (int i = 0; i < rightContext.size(); i++) {
          // replace context
          currentNgram.set(tSize - rightContext.size() + i, rightContext.get(i));
        }

      } else { // terminal words
        currentNgram.add(curID);
        if (currentNgram.size() == this.ngramOrder) {
          // compute the current word probablity, and remove it
          transitionLogP += this.lmGrammar.ngramLogProbability(currentNgram, this.ngramOrder);
          currentNgram.remove(0);
        }
      }
    }
    return transitionLogP;
  }

  /**
   * Compute the cost of a rule application. The cost of applying a rule is computed by determining
   * the n-gram costs for all n-grams created by this rule application, and summing them. N-grams
   * are created when (a) terminal words in the rule string are followed by a nonterminal (b)
   * terminal words in the rule string are preceded by a nonterminal (c) we encounter adjacent
   * nonterminals. In all of these situations, the corresponding boundary words of the node in the
   * hypergraph represented by the nonterminal must be retrieved.
   */
  private float recomputeTransition(int[] tgt, List<HGNode> antecedents) {
    // Maximum number of upcoming terminals to take out of the estimate (less-than-n-grams scored
    // in estimation).
    int head = this.ngramOrder - 1;
    // Maximum number of upcoming terminals to add to n-gram following a nonterminal.
    int tail = 0;
    List<Integer> current_ngram = new LinkedList<Integer>();
    List<Integer> duplicate_ngram = new LinkedList<Integer>();

    float log_p = 0;
    float fix = 0;
    for (int c = 0; c < tgt.length; c++) {
      int id = tgt[c];

      if (Vocabulary.nt(id)) {
        // Nonterminal symbol.
        if (!duplicate_ngram.isEmpty()) {
          fix += this.lmGrammar.sentenceLogProbability(duplicate_ngram, ngramOrder, 1);
        }
        int index = -(id + 1);
        NgramDPState state = (NgramDPState) antecedents.get(index).getDPState(this.getStateComputer());
        List<Integer> left_context = state.getLeftLMStateWords();
        List<Integer> right_context = state.getRightLMStateWords();
        if (left_context.size() != right_context.size()) {
          throw new RuntimeException("Error in reEstimateTransition: left and right contexts have "
              + "unequal lengths.");
        }
        // Left context.
        for (int i = 0; i < left_context.size(); i++) {
          int t = left_context.get(i);
          current_ngram.add(t);
          // Always calculate logP for <bo>: additional backoff weight
          if (t == BACKOFF_LEFT_LM_STATE_SYM_ID) {
            // TODO: Not sure what's happening here.
            // Number of non-state words.
            int numAdditionalBackoffWeight = current_ngram.size() - (i + 1);
            // Compute additional backoff weight.
            log_p +=
                this.lmGrammar.logProbOfBackoffState(current_ngram, current_ngram.size(),
                    numAdditionalBackoffWeight);
            if (current_ngram.size() == this.ngramOrder) {
              current_ngram.remove(0);
            }
          } else if (current_ngram.size() == this.ngramOrder) {
            // Compute the current word probability, and remove it.
            log_p += this.lmGrammar.ngramLogProbability(current_ngram, this.ngramOrder);
            current_ngram.remove(0);
          }
        }
        // Right context.
        int current_size = current_ngram.size();
        for (int i = 0; i < right_context.size(); i++) {
          // Replace context.
          current_ngram.set(current_size - right_context.size() + i, right_context.get(i));
        }
        head = this.ngramOrder - 1;
        tail = this.ngramOrder - 1;
        duplicate_ngram.clear();
      } else {
        // Terminal symbol.
        if (head > 0) {
          duplicate_ngram.add(id);
          head--;
          if (head == 0) {
            fix += this.lmGrammar.sentenceLogProbability(duplicate_ngram, ngramOrder, 1);
            duplicate_ngram.clear();
          }
        }
        current_ngram.add(id);
        tail--;
        if (current_ngram.size() == this.ngramOrder) {
          // Compute the current word probability, and remove it.
          if (tail >= 0) {
            log_p += this.lmGrammar.ngramLogProbability(current_ngram, this.ngramOrder);
          }
          current_ngram.remove(0);
        }
      }
    }
    if (!duplicate_ngram.isEmpty()) {
      fix += this.lmGrammar.sentenceLogProbability(duplicate_ngram, ngramOrder, 1);
      duplicate_ngram.clear();
    }
    return log_p - fix;
  }

  private float computeFinalTransitionLogP(NgramDPState state) {

    float res = 0.0f;
    List<Integer> currentNgram = new LinkedList<Integer>();
    List<Integer> leftContext = state.getLeftLMStateWords();
    List<Integer> rightContext = state.getRightLMStateWords();

    if (leftContext.size() != rightContext.size()) {
      throw new RuntimeException(
          "LMModel.compute_equiv_state_final_transition: left and right contexts have unequal lengths");
    }

    // ================ left context
    if (addStartAndEndSymbol) 
      currentNgram.add(START_SYM_ID);

    for (int i = 0; i < leftContext.size(); i++) {
      int t = leftContext.get(i);
      currentNgram.add(t);

      if (t == BACKOFF_LEFT_LM_STATE_SYM_ID) {// calculate logP for <bo>: additional backoff weight
        int additionalBackoffWeight = currentNgram.size() - (i + 1);
        // compute additional backoff weight
        // TOTO: may not work with the case that add_start_and_end_symbol=false
        res +=
            this.lmGrammar.logProbOfBackoffState(currentNgram, currentNgram.size(),
                additionalBackoffWeight);

      } else { // partial ngram
        // compute the current word probablity
        if (currentNgram.size() >= 2) { // start from bigram
          res += this.lmGrammar.ngramLogProbability(currentNgram, currentNgram.size());
        }
      }
      if (currentNgram.size() == this.ngramOrder) {
        currentNgram.remove(0);
      }
    }

    // ================ right context
    // switch context, we will never score the right context probablity because they are either
    // duplicate or partional ngram
    if (addStartAndEndSymbol) {
      int tSize = currentNgram.size();
      for (int i = 0; i < rightContext.size(); i++) {// replace context
        currentNgram.set(tSize - rightContext.size() + i, rightContext.get(i));
      }

      currentNgram.add(STOP_SYM_ID);
      res += this.lmGrammar.ngramLogProbability(currentNgram, currentNgram.size());
    }
    return res;
  }


  /*
   * in general: consider all the complete ngrams, and all the incomplete-ngrams that WILL have sth
   * fit into its left side, soif the left side of incomplete-ngrams is a ECLIPS, then ignore the
   * incomplete-ngramsif the left side of incomplete-ngrams is a Non-Terminal, then consider the
   * incomplete-ngramsif the left side of incomplete-ngrams is boundary of a rule, then consider the
   * incomplete-ngrams
   */
  private float estimateRuleLogProb(int[] enWords) {
    float estimate = 0.0f;
    boolean considerIncompleteNgrams = true;
    List<Integer> words = new ArrayList<Integer>();
    boolean skipStart = (enWords[0] == START_SYM_ID);

    for (int c = 0; c < enWords.length; c++) {
      int curWrd = enWords[c];
      /*
       * if (c_wrd == Symbol.ECLIPS_SYM_ID) { estimate += score_chunk( words,
       * consider_incomplete_ngrams, skip_start); consider_incomplete_ngrams = false; //for the LM
       * bonus function: this simply means the right state will not be considered at all because all
       * the ngrams in right-context will be incomplete words.clear(); skip_start = false; } else
       */if (Vocabulary.nt(curWrd)) {
        estimate += scoreChunkLogP(words, considerIncompleteNgrams, skipStart);
        considerIncompleteNgrams = true;
        words.clear();
        skipStart = false;
      } else {
        words.add(curWrd);
      }
    }
    estimate += scoreChunkLogP(words, considerIncompleteNgrams, skipStart);
    return estimate;
  }


  /**
   * TODO: This does not work when addStart == true or addEnd == true
   **/
  private float estimateStateLogProb(NgramDPState state, boolean addStart, boolean addEnd) {

    float res = 0.0f;
    List<Integer> leftContext = state.getLeftLMStateWords();

    if (null != leftContext) {
      List<Integer> words = new ArrayList<Integer>();;
      if (addStart == true) words.add(START_SYM_ID);
      words.addAll(leftContext);

      boolean considerIncompleteNgrams = true;
      boolean skipStart = true;
      if (words.get(0) != START_SYM_ID) {
        skipStart = false;
      }
      res += scoreChunkLogP(words, considerIncompleteNgrams, skipStart);
    }
    /*
     * if (add_start == true) { System.out.println("left context: " +Symbol.get_string(l_context) +
     * ";prob "+res); }
     */
    if (addEnd == true) {// only when add_end is true, we get a complete ngram, otherwise, all
                         // ngrams in r_state are incomplete and we should do nothing
      List<Integer> rightContext = state.getRightLMStateWords();
      List<Integer> list = new ArrayList<Integer>(rightContext);
      list.add(STOP_SYM_ID);
      float tem = scoreChunkLogP(list, false, false);
      res += tem;
      // System.out.println("right context:"+ Symbol.get_string(r_context) + "; score: " + tem);
    }
    return res;
  }



  private float scoreChunkLogP(List<Integer> words, boolean considerIncompleteNgrams,
      boolean skipStart) {
    if (words.size() <= 0) {
      return 0.0f;
    } else {
      int startIndex;
      if (!considerIncompleteNgrams) {
        startIndex = this.ngramOrder;
      } else if (skipStart) {
        startIndex = 2;
      } else {
        startIndex = 1;
      }
//      System.err.println("Estimate: " + Vocabulary.getWords(words));
      return (float) this.lmGrammar.sentenceLogProbability(words, this.ngramOrder, startIndex);
    }
  }

}
