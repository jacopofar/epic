package scalanlp.parser

import scalanlp.collection.mutable.SparseArrayMap
import scalanlp.tensor.sparse.OldSparseVector

/**
 * 
 * @author dlwh
 */
class ZeroGrammar[L](g: Grammar[L]) extends Grammar[L] {

  val index = g.index;

  private val indexedUnaryRulesByChild = fillSparseArrayMap(mkOldSparseVector(Double.NegativeInfinity));
  private val indexedUnaryRulesByParent = fillSparseArrayMap(mkOldSparseVector(Double.NegativeInfinity));
  for( (c,parentVector) <- g.allUnaryRules) {
    for( (p,v) <- parentVector.activeIterator) {
      indexedUnaryRulesByChild.getOrElseUpdate(c)(p) = 0.0;
      indexedUnaryRulesByParent.getOrElseUpdate(p)(c) = 0.0;
    }
  }


  // Mapping is Left Child -> Right Child -> Parent -> Score
  private val indexedBinaryRulesByLeftChild = (
    fillSparseArrayMap(fillSparseArrayMap(mkOldSparseVector(Double.NegativeInfinity)))
  );
  // Mapping is Left Child -> Right Child -> Parent -> Score
  private val indexedBinaryRulesByRightChild = (
      fillSparseArrayMap(fillSparseArrayMap(mkOldSparseVector(Double.NegativeInfinity)))
  );
  // Mapping is Parent -> Left Child -> Right Child ->  Score
  private val indexedBinaryRulesByParent = (
    fillSparseArrayMap(fillSparseArrayMap(mkOldSparseVector(Double.NegativeInfinity)))
  );
  for( (b,rules) <- g.allBinaryRules; (c,parentVector) <- rules) {
    for( (p,v) <- parentVector.activeIterator) {
      indexedBinaryRulesByLeftChild.getOrElseUpdate(b).getOrElseUpdate(c)(p) = 0.0;
      indexedBinaryRulesByRightChild.getOrElseUpdate(c).getOrElseUpdate(b)(p) = 0.0;
      indexedBinaryRulesByParent.getOrElseUpdate(p).getOrElseUpdate(b)(c) = 0.0;
    }
  }

  /**
   * Returns pairs of the form ((parent,child),score);
   * TODO: make these 0.0
   */
  def unaryRulesByChild(c: L) = {
    assert(c != null);
    g.unaryRulesByChild(c);
  }


  def unaryRulesByIndexedChild(c: Int) = {
    indexedUnaryRulesByChild(c);
  }

  def unaryRulesByIndexedParent(p: Int) = {
    indexedUnaryRulesByParent(p);
  }

  /**
   * Returns pairs of the form (child,score);
   * TODO: make these 0.0
   */
  def unaryRulesByParent(p: L) = {
    assert(p != null);
    g.unaryRulesByParent(p)
  }

  /**
   * Returns pairs of the form ( (parent,(left,right)),score);
   * TODO: make these 0.0
   */
  def binaryRulesByLeftChild(c: L) ={
    g.binaryRulesByLeftChild(c)
  }


  def allBinaryRules = indexedBinaryRulesByLeftChild;
  def allUnaryRules = indexedUnaryRulesByChild;

  def binaryRulesByIndexedLeftChild(b: Int) = indexedBinaryRulesByLeftChild(b);

  def binaryRulesByIndexedRightChild(c: Int): SparseArrayMap[OldSparseVector] = indexedBinaryRulesByRightChild(c);

  def binaryRuleScore(a: Int, b: Int, c: Int) = indexedBinaryRulesByParent(a)(b)(c);
  def unaryRuleScore(a: Int, b: Int) = indexedUnaryRulesByParent(a)(b);

  /** b, c **/
  def binaryRulesByIndexedParent(a: Int): SparseArrayMap[OldSparseVector] = indexedBinaryRulesByParent(a);
}