package edu.cmu.lti.oaqa.knn4qa.letor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import edu.cmu.lti.oaqa.knn4qa.giza.GizaOneWordTranRecs;
import edu.cmu.lti.oaqa.knn4qa.giza.TranRecSortByProb;
import edu.cmu.lti.oaqa.knn4qa.memdb.DocEntry;
import edu.cmu.lti.oaqa.knn4qa.memdb.ForwardIndex;
import edu.cmu.lti.oaqa.knn4qa.simil_func.TrulySparseVector;
import edu.cmu.lti.oaqa.knn4qa.utils.IdValPair;
import edu.cmu.lti.oaqa.knn4qa.utils.IdValParamByValDesc;
import edu.cmu.lti.oaqa.knn4qa.utils.VectorWrapper;

import no.uib.cipr.matrix.DenseVector;
import net.openhft.koloboke.collect.map.hash.HashIntIntMap;
import net.openhft.koloboke.collect.map.hash.HashIntIntMaps;
import net.openhft.koloboke.collect.map.hash.HashIntObjMap;
import net.openhft.koloboke.collect.map.hash.HashIntObjMaps;
import net.openhft.koloboke.collect.set.hash.HashIntSet;
import net.openhft.koloboke.collect.set.hash.HashIntSets;

public class FeatExtrModel1Similarity extends SingleFieldSingleScoreFeatExtractor {
  public static String EXTR_TYPE = "Model1Similarity";
  
  public static String GIZA_ITER_QTY = "gizaIterQty";
  public static String PROB_SELF_TRAN = "probSelfTran";
  public static String MIN_MODEL1_PROB = "minModel1Prob";
  public static String MODEL1_SUBDIR = "model1SubDir";
  public static String LAMBDA = "lambda";
  public static String OOV_PROB = "ProbOOV";
  public static String FLIP_DOC_QUERY = "flipDocQuery";
  public static String TOP_TRAN_SCORES_PER_DOCWORD_QTY = "topTranScoresPerDocWordQty";
  public static String TOP_TRAN_CANDWORD_QTY = "topTranCandWordQty";
  public static String MIN_TRAN_SCORE_PERDOCWORD = "minTranScorePerDocWord";
 
  @Override
  public String getName() {
    return this.getClass().getName();
  }
  
  @Override
  public String getFieldName() {
    return mFieldName;
  }
  
  public FeatExtrModel1Similarity(FeatExtrResourceManager resMngr, OneFeatExtrConf conf) throws Exception {
    mFieldName = conf.getReqParamStr(FeatExtrConfig.FIELD_NAME);   
    mModel1SubDir = conf.getParam(MODEL1_SUBDIR, mFieldName);
    mGizaIterQty = conf.getReqParamInt(GIZA_ITER_QTY);
    mProbSelfTran = conf.getReqParamFloat(PROB_SELF_TRAN);
    mMinModel1Prob = conf.getReqParamFloat(MIN_MODEL1_PROB);
    
    // If these guys aren't default, they can't be set too high, e.g., > 1e6
    // There might be an integer overflow then
    mTopTranScoresPerDocWordQty = conf.getParam(TOP_TRAN_SCORES_PER_DOCWORD_QTY, Integer.MAX_VALUE);
    mTopTranCandWordQty = conf.getParam(TOP_TRAN_CANDWORD_QTY, Integer.MAX_VALUE);
   // This default is a bit adhoc, but typically we are not interested in tran scores that are these small
    mMinTranScorePerDocWord = conf.getParam(MIN_TRAN_SCORE_PERDOCWORD, 1e-6f); 

    System.out.println("Computing " + mTopTranScoresPerDocWordQty + 
                        " top per doc-word scores from top " + mTopTranCandWordQty + 
                        " translations per document word, ignoring scores < " + mMinTranScorePerDocWord);
    
    mLambda = conf.getReqParamFloat(LAMBDA);
    mProbOOV = conf.getParam(OOV_PROB, 1e-9f); 
    
    mFlipDocQuery = conf.getParamBool(FLIP_DOC_QUERY);
    
    mModel1Data = resMngr.getModel1Tran(mFieldName, 
                                        mModel1SubDir,
                                        false /* no translation table flip */, 
                                        mGizaIterQty, mProbSelfTran, mMinModel1Prob);
    
    mFieldIndex = resMngr.getFwdIndex(mFieldName);
    mTopTranCache = HashIntObjMaps.<Integer []>newMutableMap(mModel1Data.mFieldProbTable.length);
  }

  @Override
  public Map<String, DenseVector> getFeatures(ArrayList<String> arrDocIds, Map<String, String> queryData)
      throws Exception {
    HashMap<String, DenseVector> res = initResultSet(arrDocIds, getFeatureQty()); 
    DocEntry queryEntry = getQueryEntry(mFieldName, mFieldIndex, queryData);
    if (queryEntry == null) return res;

    for (String docId : arrDocIds) {
      DocEntry docEntry = mFieldIndex.getDocEntry(docId);
      if (docEntry == null) {
        throw new Exception("Inconsistent data or bug: can't find document with id ='" + docId + "'");
      }  
      
      double score = mFlipDocQuery ? computeOverallScore(docEntry, queryEntry) : computeOverallScore(queryEntry, docEntry);
      
      DenseVector v = res.get(docId);
      if (v == null) {
        throw new Exception(String.format("Bug, cannot retrieve a vector for docId '%s' from the result set", docId));
      }    

      v.set(0, score);
    }  
    
    return res;
  }

  private double [] computeWordScores(int [] wordIds, DocEntry docEntry) throws Exception {
    int queryWordQty = wordIds.length;
    
    double res[] = new double[queryWordQty];
    
    float [] aSourceWordProb = new float[docEntry.mWordIds.length];        
    float sum = 0;    
    for (int ia=0; ia < docEntry.mWordIds.length; ++ia) 
      sum += docEntry.mQtys[ia];
    
    float invSum = 1/Math.max(1, sum);   
    
    for (int ia=0; ia < docEntry.mWordIds.length; ++ia) {
      aSourceWordProb[ia] = docEntry.mQtys[ia] * invSum;
    }

    for (int iq=0; iq < queryWordQty;++iq) {
      float totTranProb = 0;
      
      int queryWordId = wordIds[iq];
      
      if (queryWordId >= 0) {        
        for (int ia = 0; ia < docEntry.mWordIds.length; ++ia) {
          int answWordId = docEntry.mWordIds[ia];
          
          float oneTranProb = mModel1Data.mRecorder.getTranProb(answWordId, queryWordId);
          if (answWordId == queryWordId && mProbSelfTran - oneTranProb > Float.MIN_NORMAL) {
            throw new Exception("Bug in re-scaling translation tables: no self-tran probability for: id=" + answWordId + "!");
          }                
          if (oneTranProb >= mMinModel1Prob) {
            totTranProb += oneTranProb * aSourceWordProb[ia];
          }
        }
      }
 
      double collectProb = queryWordId >= 0 ? Math.max(mProbOOV, mModel1Data.mFieldProbTable[queryWordId]) : mProbOOV;
                                                    
      res[iq] = Math.log((1-mLambda)*totTranProb +mLambda*collectProb) - Math.log(mLambda*collectProb);
    }
    
    return res;
  }
  
  private double computeOverallScore(DocEntry queryEntry, DocEntry docEntry) throws Exception { 
    double logScore = 0;


    int queryWordQty = queryEntry.mWordIds.length;

    if (false && mTopTranScoresPerDocWordQty == Integer.MAX_VALUE) {
      // Computing unpruned score
      // TODO for some weird reason this produces different results
      // compared to the other branch with all pruning settings
      // set to their default values (i.e. Integer.MAX_VALUE for top 
      // score/translation variant numbers and zero for minimum
      // translation score.
      double queryWordScores[] = computeWordScores(queryEntry.mWordIds, docEntry);
      
      for (int iq=0; iq < queryWordQty;++iq) {                                        
        logScore += queryEntry.mQtys[iq] * queryWordScores[iq];
      }
    } else {
      // Map query IDs to QTYs
      HashIntIntMap queryWordIdQtys = HashIntIntMaps.newMutableMap();
      for (int iq=0; iq < queryWordQty;++iq) {   
        queryWordIdQtys.put(queryEntry.mWordIds[iq], queryEntry.mQtys[iq]);
      }
      
      for (IdValPair topIdScore : getTopWordIdsAndScores(docEntry)) {
        int wid = topIdScore.mId;
        if (queryWordIdQtys.containsKey(wid)) {
          logScore +=  queryWordIdQtys.get(wid) * topIdScore.mVal;
        }
      }
    }

    float queryNorm = Math.max(1, queryWordQty);
    
    return logScore / queryNorm;
  }
  
  private ArrayList<IdValPair> getTopWordIdsAndScores(DocEntry doc) throws Exception {
    HashIntSet   wordIdsHash = HashIntSets.newMutableSet();
    
    for (int wid : doc.mWordIds) {
      for (int dstWordId : getTopCandWordIds(wid)) {
        wordIdsHash.add(dstWordId);
      }
    }
    
    int topCandWordIds[] = wordIdsHash.toIntArray();
    double topCandWorIdsScores[] = computeWordScores(topCandWordIds, doc);
    
    ArrayList<IdValPair> res = new ArrayList<IdValPair>();
    
    for (int i = 0; i < topCandWordIds.length; ++i) {
      double score = topCandWorIdsScores[i];
      if (score > mMinTranScorePerDocWord) {
        res.add(new IdValPair(topCandWordIds[i], (float)score));
      }
    }
    
    res.sort(mDescByValComp);

    if (mTopTranScoresPerDocWordQty < Integer.MAX_VALUE) {
    
      int maxQty = doc.mWordIds.length * mTopTranScoresPerDocWordQty;
      while (res.size() > maxQty) {
        res.remove(res.size()-1);
      }
      
    }
    /*
    for (int i = 0; i < res.size(); ++i) {
      System.out.println(res.get(i).toString());
    }
    System.out.println("=============");
    */
    
    return res;
   
  }
  
  /**
   * Return words with highest translation scores + the word itself (with respect to a specific word).
   * The result size is at most {@link mTopTranCandWordQty}.
   * This function caches results in a thread-safe fashion.
   * 
   * @param wordId       a word ID
   * 
   * @return an integer array of word IDs.
   */
  private synchronized Integer[] getTopCandWordIds(int wordId) {
    
    if (!mTopTranCache.containsKey(wordId)) {
      
      Integer res [] = {};
      GizaOneWordTranRecs tranRecs = mModel1Data.mRecorder.getTranProbs(wordId);
      
      if (tranRecs != null) {
        
        boolean hasNoSelfTran = true;
        for (int dstWordId : tranRecs.mDstIds) {
          if (dstWordId == wordId) {
            hasNoSelfTran = false;
            break;
          }
        }
        
        TranRecSortByProb tranRecSortedByProb[] = new TranRecSortByProb[tranRecs.mDstIds.length + (hasNoSelfTran ? 1:0)];
        for (int i = 0; i < tranRecs.mDstIds.length; ++i) {
          tranRecSortedByProb[i] = new TranRecSortByProb(tranRecs.mDstIds[i], tranRecs.mProbs[i]);
        }
        if (hasNoSelfTran) {
          tranRecSortedByProb[tranRecs.mDstIds.length] = new TranRecSortByProb(wordId, mProbSelfTran);
        }
        Arrays.sort(tranRecSortedByProb); // Descending by probability
        
        int resQty = Math.min(mTopTranCandWordQty, tranRecSortedByProb.length);
        res = new Integer[resQty];
        for (int i = 0; i < resQty; ++i) {
          res[i] = tranRecSortedByProb[i].mDstWorId; 
        }

      }
      
      mTopTranCache.put(wordId, res);
      return res;
    }
    
    return mTopTranCache.get(wordId);
  }

  /**
   * This feature-generator creates sparse-vector feature representations.
   * 
   */
  @Override
  public boolean isSparse() {
    return true;
  }

  /**
   * Dimensionality is zero, because we generate sparse features.
   * 
   */
  @Override
  public int getDim() {
    return 0;
  }

  @Override
  public VectorWrapper getFeatInnerProdVector(DocEntry e, boolean isQuery) throws Exception {

    if (mFlipDocQuery) {
      isQuery = !isQuery;
    }
    if (isQuery) {
      return getQueryFeatureVectorsForInnerProd(e);
    } else {
      return getDocFeatureVectorsForInnerProd(e);
    }
 
  }

  private VectorWrapper getDocFeatureVectorsForInnerProd(DocEntry doc) throws Exception {
    // 1. Get terms with sufficiently high translation probability with
    //    respect to the document
    ArrayList<IdValPair> topIdsScores = getTopWordIdsAndScores(doc);
    
    Collections.sort(topIdsScores); // ascending by ID
    
    int wqty = topIdsScores.size();
    
    TrulySparseVector res = new TrulySparseVector(wqty);
    
    int k = 0;
    for (IdValPair e : topIdsScores) {
      res.mIDs[k] = e.mId;
      res.mVals[k] = e.mVal;
      k++;
    }
    
    return new VectorWrapper(res);
  }

  private VectorWrapper getQueryFeatureVectorsForInnerProd(DocEntry e) {
    int queryWordQty = e.mWordIds.length; 
    
    int nonzWordQty = 0;

    for (int k = 0; k < e.mWordIds.length; ++k) {
      if (e.mWordIds[k] >= 0) {
        nonzWordQty++;
      }
    }
    TrulySparseVector res = new TrulySparseVector(nonzWordQty);
    
    float inv = 1.0f/Math.max(1, queryWordQty);
    
    int idx = 0;
    for (int k = 0; k < queryWordQty; ++k) {
      int wordId = e.mWordIds[k];
      if (wordId >= 0) {
        res.mIDs[idx] = wordId;
        res.mVals[idx] = e.mQtys[k] * inv;
        idx++;
      }
    }
    
    return new VectorWrapper(res);
  }
  
  final ForwardIndex    mFieldIndex;
  final String          mFieldName;
  final String          mModel1SubDir;
  final Model1Data      mModel1Data;
  final int             mGizaIterQty;
  final float           mProbSelfTran;
  final float           mMinModel1Prob;
  final float           mLambda;
  final float           mProbOOV;
  final boolean         mFlipDocQuery;
  
  final int             mTopTranScoresPerDocWordQty;
  final int             mTopTranCandWordQty;
  final float           mMinTranScorePerDocWord;
  
  final IdValParamByValDesc mDescByValComp = new IdValParamByValDesc();
  
  final HashIntObjMap<Integer []> mTopTranCache;
}
