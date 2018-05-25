package streaming.dsl.mmlib.algs

import org.apache.spark.ml.linalg.Vectors
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.{ArrayType, DoubleType}
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}
import streaming.dsl.mmlib.SQLAlg
import streaming.dsl.mmlib.algs.MetaConst._
import streaming.dsl.mmlib.algs.feature.DoubleFeature
import streaming.dsl.mmlib.algs.meta.ScaleMeta

/**
  * Created by allwefantasy on 24/5/2018.
  */
class SQLNormalizeInPlace extends SQLAlg with Functions {

  def internal_train(df: DataFrame, params: Map[String, String]) = {
    val path = params("path")
    val metaPath = getMetaPath(path)
    saveTraningParams(df.sparkSession, params, metaPath)
    val inputCols = params.getOrElse("inputCols", "").split(",")
    val method = params.getOrElse("method", "standard")
    val removeOutlierValue = params.getOrElse("removeOutlierValue", "false").toBoolean
    require(!inputCols.isEmpty, "inputCols is required when use SQLScalerInPlace")
    var newDF = df
    if (removeOutlierValue) {
      newDF = DoubleFeature.killOutlierValue(df, metaPath, inputCols)
    }
    newDF = DoubleFeature.normalize(df, metaPath, inputCols, method, params)
    newDF
  }

  override def train(df: DataFrame, path: String, params: Map[String, String]): Unit = {
    val newDF = internal_train(df, params + ("path" -> path))
    newDF.write.mode(SaveMode.Overwrite).parquet(getDataPath(path))
  }

  override def load(spark: SparkSession, _path: String, params: Map[String, String]): Any = {
    //load train params
    val path = getMetaPath(_path)
    val (trainParams, df) = getTranningParams(spark, path)
    val inputCols = trainParams.getOrElse("inputCols", "").split(",").toSeq
    val method = trainParams.getOrElse("method", "standard")
    val removeOutlierValue = trainParams.getOrElse("removeOutlierValue", "false").toBoolean

    val scaleFunc = DoubleFeature.getModelNormalizeForPredict(spark, path, inputCols, method, trainParams)

    var meta = ScaleMeta(trainParams, null, scaleFunc)

    if (removeOutlierValue) {
      val removeOutlierValueFunc = DoubleFeature.getModelOutlierValueForPredict(spark, path, inputCols, trainParams)
      meta = meta.copy(removeOutlierValueFunc = removeOutlierValueFunc)
    }
    meta
  }

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = {

    val meta = _model.asInstanceOf[ScaleMeta]
    val removeOutlierValue = meta.trainParams.getOrElse("removeOutlierValue", "false").toBoolean
    val inputCols = meta.trainParams.getOrElse("inputCols", "").split(",").toSeq

    val f = (values: Seq[Double]) => {
      val newValues = if (removeOutlierValue) {
        values.zipWithIndex.map { v =>
          meta.removeOutlierValueFunc(v._1, inputCols(v._2))
        }
      } else values
      meta.scaleFunc(Vectors.dense(newValues.toArray)).toArray
    }
    UserDefinedFunction(f, ArrayType(DoubleType), Some(Seq(ArrayType(DoubleType))))
  }
}
