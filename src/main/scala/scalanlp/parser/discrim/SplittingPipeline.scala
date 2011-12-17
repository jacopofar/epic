package scalanlp.parser.discrim

import scalanlp.util._
import scalanlp.parser._
import java.io._
import projections.{ProjectionIndexer, GrammarProjections}
import scalala.tensor.dense.DenseVector
import scalanlp.trees.UnaryChainRemover.ChainReplacer
import scalala.library.Library
import scalanlp.parser.ParseChart._
import scalala.library.Library._
import scalanlp.optimize.CachedBatchDiffFunction
import scalala.tensor.{Counter, Counter2}
import scalala.tensor.::
import logging._

/**
 * Runs a parser that can conditionally split labels
 *
 * @author dlwh
 */
object SplittingPipeline extends ParserPipeline {

  type MyFeaturizer = Featurizer[(String,Seq[Int]),String]
  type MyObjective = LatentDiscrimObjective[String,(String,Seq[Int]),String]

  case class SpecificParams(lastParser: File = null, fracToSplit:Double = 0.5)
  implicit def specificManifest = manifest[SpecificParams]

  type Params = LatentParams[SpecificParams];
  protected lazy val paramManifest = { manifest[Params]}

  def mkObjective(params: SplittingPipeline.Params,
                  latentFeaturizer: SplittingPipeline.MyFeaturizer,
                  trainTrees: IndexedSeq[TreeInstance[String, String]],
                  indexedProjections: GrammarProjections[String, (String, Seq[Int])],
                  xbarParser: ChartBuilder[ParseChart.LogProbabilityParseChart, String, String],
                  openTags: Set[(String, Seq[Int])],
                  closedWords: Set[String]) = {
    val r = new LatentDiscrimObjective(latentFeaturizer,
      trainTrees,
      indexedProjections,
      xbarParser,
      openTags,
      closedWords) with ConfiguredLogging;

    r
  }


  type Sym = (String,Seq[Int])
  def getFeaturizer(params: SplittingPipeline.Params,
                    initLexicon: Counter2[String, String, Double],
                    initBinaries: Counter2[String, BinaryRule[String], Double],
                    initUnaries: Counter2[String, UnaryRule[String], Double],
                    numStates: Int) = {
    val factory = params.featurizerFactory;
    val featurizer = factory.getFeaturizer(initLexicon, initBinaries, initUnaries);
    val latentFeaturizer = new MultiscaleFeaturizer(featurizer)
    val weightsPath = params.oldWeights;
    println("old weights: " + weightsPath);
    if(weightsPath == null) {
      latentFeaturizer
    } else {
      val weights = readObject[(DenseVector[Double],Counter[Feature[(String,Seq[Int]),String],Double])](weightsPath)._2;
      println(weights.size,weights.valuesIterator.count(_ == 0.0),weights.valuesIterator.count(_.abs < 1E-4))
      def projectFeature(f: Feature[Sym,String]) = f match {
        case SubstateFeature(k,states) if params.splitFactor > 1 => SubstateFeature(k,states.map(_.dropRight(1)))
        case _ => f
      }

      new CachedWeightsFeaturizer(latentFeaturizer, weights, randomize= false, randomizeZeros = true);
    }
  }

  def split(sym: Sym) = if(sym._1 == "") Seq(sym._1 -> Seq(0)) else Seq((sym._1,sym._2 :+ 0), (sym._1,sym._2 :+ 1))
  def initialSplit(syms: Index[String]) = {
    def make(sym: String) = sym -> Seq.empty[Int]
    val init = Index(syms.map(make))
    ProjectionIndexer.fromSplitter(init, split)
  }

  def splitLabels(oneStepProjections: ProjectionIndexer[Sym,Sym],
                  builder: SimpleChartParser[String,Sym,String],
                  trees: IndexedSeq[TreeInstance[String,String]], fracToSplit: Double = 0.5) = {
    val splitter = new ConditionalLabelSplitter(oneStepProjections, split, fracToSplit)
    val splitTrees = trees.map { i =>
      i.copy(tree = i.tree.map(l => builder.projections.labels.refinementsOf(l).toSeq), spanScorer = SpanScorer.identity)
    }
    splitter.splitLabels(builder.builder,splitTrees)
  }

  def trainParser(trainTrees: IndexedSeq[TreeInstance[String,String]],
                  devTrees: IndexedSeq[TreeInstance[String,String]],
                  unaryReplacer : ChainReplacer[String],
                  params: Params) = {

    val (initLexicon,initBinaries,initUnaries) = GenerativeParser.extractCounts(trainTrees);
    import params._;

    val xbarParser = parser.optParser.getOrElse {
      val grammar: Grammar[String] = Grammar(Library.logAndNormalizeRows(initBinaries),Library.logAndNormalizeRows(initUnaries));
      val lexicon = new SimpleLexicon(initLexicon);
      new CKYChartBuilder[LogProbabilityParseChart,String,String]("",lexicon,grammar,ParseChart.logProb);
    }

    val oneStepProjections = if(specific.lastParser == null) {
      initialSplit(xbarParser.grammar.labelIndex)
    } else {
      val oldParser = readObject[SimpleChartParser[String,Sym,String]](params.specific.lastParser)
      val lastProjectionsFile = new File(params.specific.lastParser.getParentFile.getParentFile,"projections.ser")
      val lastOneStep = readObject[ProjectionIndexer[Sym,Sym]](lastProjectionsFile)
      splitLabels(lastOneStep, oldParser, trainTrees, params.specific.fracToSplit)
    }
    writeObject(new File("projections.ser"), oneStepProjections)

    def unsplit(s: (String,Any)) = s._1
    val labelProjections = ProjectionIndexer(xbarParser.grammar.labelIndex, oneStepProjections.fineIndex, unsplit)
    def split(s: String) = labelProjections.refinementsOf(s)
    for(s <- oneStepProjections.coarseIndex) println(s -> oneStepProjections.refinementsOf(s))
    println("<<<<<")
    for(s <- labelProjections.coarseIndex) println(s -> labelProjections.refinementsOf(s))

    val indexedProjections = {
      val raw = GrammarProjections(xbarParser.grammar, split _, unsplit _ )
      GrammarProjections(labelProjections, raw.rules)
    };

    val latentFeaturizer: MyFeaturizer = getFeaturizer(params, initLexicon, initBinaries, initUnaries, numStates)

    val openTags = Set.empty ++ {
      for(t <- initLexicon.nonzero.keys.map(_._1) if initLexicon(t,::).size > 50; t2 <- split(t).iterator ) yield t2;
    }

    val closedWords = Set.empty ++ {
      val wordCounts = sum(initLexicon)
      wordCounts.nonzero.pairs.iterator.filter(_._2 > 5).map(_._1);
    }

    val obj = mkObjective(params, latentFeaturizer, trainTrees, indexedProjections, xbarParser, openTags, closedWords)

    val optimizer = opt.minimizer(obj);

    val init = obj.initialWeightVector + 0.0;

    import scalanlp.optimize.RandomizedGradientCheckingFunction;
    val rand = new RandomizedGradientCheckingFunction(obj,1E-4);
    def evalAndCache(pair: (optimizer.State,Int) ) {
      val (state,iter) = pair;
      val weights = state.x;
      if(iter % iterPerValidate == 0) {
        cacheWeights(params, obj,weights, iter);
        quickEval(obj, unaryReplacer, devTrees, weights);
      }
    }


    val cachedObj = new CachedBatchDiffFunction[DenseVector[Double]](obj);

    for( (state,iter) <- optimizer.iterations(cachedObj,init).take(maxIterations).zipWithIndex.tee(evalAndCache _);
         if iter != 0 && iter % iterationsPerEval == 0) yield try {
      val parser = obj.extractParser(state.x)
      ("MultiScale-" + iter.toString,parser)
    } catch {
      case e => println(e);e.printStackTrace(); throw e;
    }
  }


  def cacheWeights(params: Params, obj: MyObjective, weights: DenseVector[Double], iter: Int) = {
    println("Zeros:" + weights.size,weights.valuesIterator.count(_ == 0), weights.valuesIterator.count(_.abs < 1E-4))
    val name = if(iter/10 % 2 == 0) "weights-b.ser" else "weights-a.ser"
    writeObject( new File(name), weights -> obj.indexedFeatures.decode(weights))
  }

  def quickEval(obj: AbstractDiscriminativeObjective[String,(String,Seq[Int]),String],
                unaryReplacer : ChainReplacer[String],
                devTrees: Seq[TreeInstance[String,String]], weights: DenseVector[Double]) {
    println("Validating...");
    val parser = obj.extractParser(weights);
    val fixedTrees = devTrees.take(400).toIndexedSeq;
    val results = ParseEval.evaluate(fixedTrees, parser, unaryReplacer);
    println("Validation : " + results)
  }
}