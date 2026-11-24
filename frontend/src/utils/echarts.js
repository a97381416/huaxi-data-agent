import * as echarts from 'echarts/core'
import { LineChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * ECharts 按需引入（设计 §6：图表全部查分钟级聚合表，Dashboard 双趋势图 + dataZoom）。
 * 统一在此注册用到的 chart/component，业务组件 import echarts from '@/utils/echarts'。
 */
echarts.use([
  LineChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  DataZoomComponent,
  CanvasRenderer
])

export default echarts
