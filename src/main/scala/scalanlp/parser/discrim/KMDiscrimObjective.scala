package scalanlp.parser
package discrim

import scalala.tensor.dense.DenseVector
import scalanlp.trees._
import scalanlp.optimize._

import InsideOutside._
import projections._
import scalanlp.trees.UnaryChainRemover.ChainReplacer;

import ParseChart.LogProbabilityParseChart;

import scalanlp.util._;
import logging._
import java.io._
import logging.ConfiguredLogging
import scalala.library.Library
import Library.sum
import scalala.tensor.{Counter,::}
import projections.GrammarProjections
import collection.immutable.Set
import splitting.StateSplitting

/**
 * The objective function for K&M annotated log-linear parsers with no substates
 * @author dlwh
 */
class KMDiscrimObjective[L,W](feat: Featurizer[L,W],
                            trees: IndexedSeq[TreeInstance[L,W]],
                            coarseParser: ChartBuilder[LogProbabilityParseChart, L, W],
                            openTags: Set[L],
                            closedWords: Set[W])
        extends LatentDiscrimObjective[L,L,W](feat,trees,GrammarProjections.identity(coarseParser.grammar),coarseParser, openTags,closedWords) {

  override def treeToExpectedCounts(g: Grammar[L],
                           lexicon: Lexicon[L,W],
                           t: BinarizedTree[L],
                           words: Seq[W],
                           spanScorer: SpanScorer[L] = SpanScorer.identity):ExpectedCounts[W] = {
    val expectedCounts = new ExpectedCounts[W](g)
    var score = 0.0;
    for(t2 <- t.allChildren) {
      t2 match {
        case BinaryTree(a,bt@ Tree(b,_),Tree(c,_)) =>
          val r = g.index(BinaryRule(a,b,c))
          expectedCounts.ruleCounts(r) += 1
          score += g.ruleScore(r);
        case UnaryTree(a,Tree(b,_)) =>
          val r = g.index(UnaryRule(a,b))
          expectedCounts.ruleCounts(r) += 1
          score += g.ruleScore(r);
        case n@NullaryTree(a) =>
          val aI = g.labelIndex(a)
          val w = words(n.span.start);
          expectedCounts.wordCounts.getOrElseUpdate(aI)(w) += 1
          score += lexicon.wordScore(g.labelIndex.get(aI), w);
      }
    }
    expectedCounts.logProb = score;
    expectedCounts
  }

}


import scalanlp.optimize.FirstOrderMinimizer._;
object KMDiscriminativePipeline extends ParserPipeline {

  protected val paramManifest = manifest[Params];
  case class Params(parser: ParserParams.BaseParser[String],
                    opt: OptParams,
                    pipeline: KMPipeline,
                    featurizerFactory: FeaturizerFactory[AnnotatedLabel,String] = new PlainFeaturizerFactory[AnnotatedLabel](),
                    iterationsPerEval: Int = 50,
                    maxIterations: Int = 201,
                    iterPerValidate: Int = 10,
                    oldWeights: File = null,
                    splitFactor:Int = 1);



  def trainParser(trainTrees: IndexedSeq[TreeInstance[String,String]],
                  validate: Parser[String,String]=>ParseEval.Statistics,
                  params: Params): Iterator[(String, SimpleChartParser[String, AnnotatedLabel, String])] = {

    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees);

    val xbarParser = params.parser.optParser getOrElse {
      val grammar = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    import params._;

    val factory = params.featurizerFactory;

    val transformed = trainTrees.par.map { ti =>
      val t = pipeline(ti.tree,ti.words)
      TreeInstance(ti.id,t,ti.words)
    }.seq
    val (words,binary,unary) = GenerativeParser.extractCounts(transformed);
    val grammar = Grammar(Library.logAndNormalizeRows(binary),Library.logAndNormalizeRows(unary));
    val lexicon = new SignatureLexicon(words, EnglishWordClassGenerator, 5);
    val builder = CKYChartBuilder(AnnotatedLabel(""),lexicon,grammar).withCharts(ParseChart.logProb)
    println(grammar.labelIndex)
    val proj = GrammarProjections(xbarParser.grammar,grammar,{(_:AnnotatedLabel).label})

    val featurizer = factory.getFeaturizer(words, binary, unary);

    val newTrees = (trainTrees zip transformed).toArray.par.map { case (ti,newTree) =>
      val scorer = new ProjectingSpanScorer(proj,ti.spanScorer)
      TreeInstance(ti.id,newTree.tree,newTree.words,scorer)
    }.seq


    val openTags: Set[AnnotatedLabel] = Set.empty ++ {
      for(t <- words.nonzero.keys.iterator.map(_._1) if words(t, ::).size > 50) yield t;
    }

    val closedWords:Set[String] = Set.empty ++ {
      val wordCounts = sum(words)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 10).map(_._1);
    }

    val obj = new KMDiscrimObjective[AnnotatedLabel,String](featurizer, newTrees,  builder, openTags, closedWords) with ConfiguredLogging;

    def cacheWeights(weights: DenseVector[Double], iter: Int) {
      val name = if(iter % (2*iterPerValidate) == 0) {
        "weights-a"
      } else {
        "weights-b"
      }

      writeObject( new File(name+".ser"), weights -> obj.indexedFeatures.decode(weights))
    }

    type OptState = FirstOrderMinimizer[DenseVector[Double],BatchDiffFunction[DenseVector[Double]]]#State
    def evalAndCache(pair: (OptState,Int) ) {
      val (state,iter) = pair;
      val weights = state.x;
      if(iter % iterPerValidate == 0) {
        cacheWeights(weights, iter)
        println("Validating...");
        val parser = obj.extractParser(weights);
        val decoder = new MaxConstituentDecoder[String,AnnotatedLabel,String](proj)
        val newParser = new SimpleChartParser[String,AnnotatedLabel,String](parser.builder,decoder, proj)
        println(validate(newParser))
      }
    }


    // new LBFGS[Int,DenseVector[Double]](iterationsPerEval,5) with ConsoleLogging;
    val init = obj.initialWeightVector;
    val rand = new RandomizedGradientCheckingFunction(obj, 0.1);

    for( (state,iter) <- params.opt.iterations(obj,init).take(maxIterations).zipWithIndex.tee(evalAndCache _);
         if iter != 0 && iter % iterationsPerEval == 0) yield {
       val parser = obj.extractParser(state.x);
       val decoder = new MaxConstituentDecoder[String,AnnotatedLabel,String](proj)
        val newParser = new SimpleChartParser[String,AnnotatedLabel,String](parser.builder,decoder, proj)
       ("KM-disc"+iter, newParser);
    }

  }
}