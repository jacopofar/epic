package scalanlp.parser
package discrim

import scalanlp.trees.UnaryChainRemover.ChainReplacer

import scalanlp.optimize.FirstOrderMinimizer.OptParams;


import scalala.tensor.Vector;
import scalanlp.collection.mutable.TriangularArray
import scalanlp.util._
import scalanlp.trees._
import scalanlp.parser.InsideOutside.ExpectedCounts
import scalanlp.parser.ParseChart.LogProbabilityParseChart
import structpred._;
import edu.berkeley.nlp.util.CounterInterface;
import scala.collection.JavaConversions._
import java.io.{File, FileWriter}
import projections.{ProjectingSpanScorer, ProjectionIndexer}
import scalala.library.Library
import Library.sum
import scalala.tensor.::


/**
 *
 * @author dlwh
 */
object StructuredTrainer extends ParserTrainer {

  protected val paramManifest = manifest[Params];
  case class Params(parser: ParserParams.BaseParser,
                    opt: OptParams,
                    featurizerFactory: FeaturizerFactory[String,String] = new PlainFeaturizerFactory[String],
                    iterationsPerEval: Int = 50,
                    maxIterations: Int = 30,
                    iterPerValidate: Int = 10,
                    C: Double = 1E-4,
                    epsilon: Double = 1E-2,
                    mira: Boolean = false,
                    smoMiniBatch: Boolean = true,
                    innerSmoIters:Int = 10,
                    minDecodeToSmoTimeRatio:Double = 0.0,
                    miniBatchSize: Int = 1);

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String,String]],
                  devTrees: IndexedSeq[TreeInstance[String,String]],
                  unaryReplacer: ChainReplacer[String], params: Params) = {
    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees);

    val xbarParser = {
      val grammar = new GenerativeGrammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }


    val projections = params.parser.optParser.map(p => ProjectionIndexer.simple(p.index)) getOrElse {
      ProjectionIndexer.simple(xbarParser.index);
    }
    val indexedProjections = ProjectionIndexer.simple(xbarParser.grammar.index);

    val factory = params.featurizerFactory;
    val featurizer = factory.getFeaturizer(initLexicon, initBinaries, initUnaries);

    val openTags = Set.empty ++ {
      for(t <- initLexicon.domain._1 if initLexicon(t, ::).size > 50) yield t;
    }

    println("Basexxx: " + xbarParser.lexicon.tagScores("messages"));
    println(openTags);

    val closedWords = Set.empty ++ {
      val wordCounts = sum(initLexicon)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 10).map(_._1);
    }


    val obj = new DiscrimObjective(featurizer, trainTrees, xbarParser, openTags, closedWords);
    val weights = obj.initialWeightVector;
    val pp = obj.extractMaxParser(weights);
    val model = new ParserLinearModel[ParseChart.LogProbabilityParseChart](projections,devTrees.toIndexedSeq, unaryReplacer, obj);

    NSlackSVM.SvmOpts.smoMiniBatch = params.smoMiniBatch;
    NSlackSVM.SvmOpts.innerSmoIters = params.innerSmoIters;
    NSlackSVM.SvmOpts.minDecodeToSmoTimeRatio = params.minDecodeToSmoTimeRatio;
    NSlackSVM.SvmOpts.miniBatchSize = params.miniBatchSize;

    val learner = if (params.mira) new MIRA[TreeInstance[String,String]](true,false,params.C)
                  else new NSlackSVM[TreeInstance[String,String]](params.C,params.epsilon,10, new NSlackSVM.SvmOpts)
    val finalWeights = learner.train(model.denseVectorToCounter(weights),model, trainTrees, params.maxIterations);
    val parser = model.getParser(finalWeights);

    Iterator(("Base",obj.extractParser(weights)),("Structured",parser));
  }

  import java.{util=>ju}


  class ParserLinearModel[Chart[X]<:ParseChart[X]](projections: ProjectionIndexer[String,String],
                                                   devTrees: IndexedSeq[TreeInstance[String,String]],
                                                   unaryReplacer: ChainReplacer[String],
                                                   obj: DiscrimObjective[String,String]) extends LossAugmentedLinearModel[TreeInstance[String,String]] {
    type Datum = TreeInstance[String,String];

    def getParser(weights: CounterInterface[java.lang.Integer]) = {
      val parser = obj.extractMaxParser(weightsToDenseVector(weights));
      val realParser = new ChartParser(parser.builder, new SimpleViterbiDecoder[String,String](parser.builder.grammar), projections, false);
      realParser
    }

    val timeIn : Long = System.currentTimeMillis();

    override def startIteration(t: Int) {
      val parser = getParser(weights);
      val result = ParseEval.evaluateAndLog(devTrees,parser, "Dev-" +t,unaryReplacer);
      val out = new FileWriter(new File("LEARNING_CURVE"), true);
      val totalTime = (System.currentTimeMillis() - timeIn)/1000.0;
      out.append( t + "\t" + result.precision + "\t" + result.recall + "\t" + result.f1 + "\t" + totalTime + "\n");
      out.close();
    }
    
    override def getLossAugmentedUpdateBundle(datum: Datum, lossWeight: Double): UpdateBundle = {
      getLossAugmentedUpdateBundleBatch(ju.Collections.singletonList(datum), 1, lossWeight).get(0);
    }

    override def getLossAugmentedUpdateBundleBatch(data: ju.List[Datum], numThreads: Int,
                                           lossWeight: Double): ju.List[UpdateBundle] = {
      val parser = getParser(weights);
      val logSumBuilder = parser.builder.withCharts(ParseChart.logProb);
      data.toIndexedSeq.par.map { (ti:TreeInstance[String,String]) =>
        val TreeInstance(_,goldTree,words,coarseFilter) = ti;
        val lossScorer = lossAugmentedScorer(lossWeight,projections,goldTree);
        val scorer = SpanScorer.sum(coarseFilter, lossScorer)

//        val logSumCounts = obj.wordsToExpectedCounts(words,logSumBuilder,coarseFilter);

        val guessTree = parser.bestParse(words,scorer);
        val (goldCounts,_) = treeToExpectedCounts(parser,ti);
//        val (guessCounts,loss) = treeToExpectedCounts(parser,guessTree,words,unweightedLossScorer);
        val guessCounts = obj.wordsToExpectedCounts(words,logSumBuilder,coarseFilter);

        val goldFeatures = expectedCountsToFeatureVector(obj.indexedFeatures, goldCounts);
        val guessFeatures = expectedCountsToFeatureVector(obj.indexedFeatures, guessCounts);

        val bundle = new UpdateBundle;
        bundle.gold = denseVectorToCounter(goldFeatures);
        bundle.guess = denseVectorToCounter(guessFeatures);
        bundle.loss = 0.0 //loss;

        bundle
      }.seq
    }


    override def getUpdateBundle(datum: Datum): UpdateBundle = getLossAugmentedUpdateBundle(datum, 0.0);
    override def getUpdateBundleBatch(datum: ju.List[Datum], numThreads: Int): ju.List[UpdateBundle] = {
      getLossAugmentedUpdateBundleBatch(datum,numThreads,0.0);

    }

    @scala.reflect.BeanProperty
    var weights: CounterInterface[java.lang.Integer] = _;

    def weightsToDenseVector(weights: CounterInterface[java.lang.Integer]) = {
      val vector = obj.indexedFeatures.mkDenseVector(0.0);
      for(entry <- weights.entries) {
        vector(entry.getKey.intValue) = entry.getValue.doubleValue;
      }
      vector
    }

    def denseVectorToCounter(vec: Vector[Double]) = {
      val res = new edu.berkeley.nlp.util.Counter[java.lang.Integer]();
      for( (i,v) <- vec.nonzero.pairs.iterator) {
        res.setCount(i,v);
      }
      res
    }

  }

  def lossAugmentedScorer[L](lossWeight: Double, projections: ProjectionIndexer[L,L], goldTree: Tree[L]): SpanScorer[L] = {
    val gold = new TriangularArray(goldTree.span.end+1,collection.mutable.BitSet());
    for( t <- goldTree.allChildren) try {
      gold(t.span.start,t.span.end) += projections.project(projections.fineIndex(t.label));
    } catch {
      case e: Throwable => throw new RuntimeException("rrrr " + t.label + " " + projections.fineIndex(t.label), e);
    }

    val scorer = new SpanScorer[L] {
      def scoreLexical(begin: Int, end: Int, tag: Int) = 0.0

      def scoreUnaryRule(begin: Int, end: Int, parent: Int, child: Int) = {
        if(gold(begin,end)(parent)) 0.0
        else lossWeight;
      }

      def scoreBinaryRule(begin: Int, split: Int, end: Int, parent: Int, leftChild: Int, rightChild: Int) =  {
        if(gold(begin,end)(parent)) 0.0
        else lossWeight;
      }
    }

    scorer
  }

  def treeToExpectedCounts[L,W](parser: ChartParser[L,L,W],
                                treeInstance: TreeInstance[L,W]):(ExpectedCounts[W],Double) = {
    val TreeInstance(_,lt,words,spanScorer) = treeInstance;
    val g = parser.builder.grammar;
    val lexicon = parser.builder.lexicon;
    val expectedCounts = new ExpectedCounts[W](g)
    val t = lt.map(g.index);
    var score = 0.0;
    var loss = 0.0;
    for(t2 <- t.allChildren) {
      t2 match {
        case BinaryTree(a,bt@ Tree(b,_),Tree(c,_)) =>
          expectedCounts.binaryRuleCounts.getOrElseUpdate(a).getOrElseUpdate(b)(c) += 1
          score += g.binaryRuleScore(a,b,c);
          loss += spanScorer.scoreBinaryRule(t2.span.start,bt.span.end, t2.span.end, a,b,c);
        case UnaryTree(a,Tree(b,_)) =>
          expectedCounts.unaryRuleCounts.getOrElseUpdate(a)(b) += 1
          score += g.unaryRuleScore(a,b);
          loss += spanScorer.scoreUnaryRule(t2.span.start,t2.span.end,a,b);
        case n@NullaryTree(a) =>
          val w = words(n.span.start);
          expectedCounts.wordCounts.getOrElseUpdate(a)(w) += 1
          score += lexicon.wordScore(g.index.get(a), w);
          loss += spanScorer.scoreLexical(t2.span.start,t2.span.end,a);
      }
    }
    expectedCounts.logProb = score;
    (expectedCounts,loss);
  }

  def expectedCountsToFeatureVector[L,W](indexedFeatures: FeatureIndexer[L,W], ecounts: ExpectedCounts[W]) = {
    val result = indexedFeatures.mkSparseVector();

    // binaries
    for( (a,bvec) <- ecounts.binaryRuleCounts;
         (b,cvec) <- bvec;
         (c,v) <- cvec.nonzero.pairs) {
      result += (indexedFeatures.featuresFor(a,b,c) * v);
    }

    // unaries
    for( (a,bvec) <- ecounts.unaryRuleCounts;
         (b,v) <- bvec.nonzero.pairs) {
      result += (indexedFeatures.featuresFor(a,b) * v);
    }

    // lex
    for( (a,ctr) <- ecounts.wordCounts;
         (w,v) <- ctr.nonzero.pairs) {
      result += (indexedFeatures.featuresFor(a,w) * v);
    }

    result;
  }

}
