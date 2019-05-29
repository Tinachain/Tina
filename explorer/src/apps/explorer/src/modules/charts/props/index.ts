export interface ChartData {
  labels: any[]
  datasets: [
    {
      label: string
      borderColor: string
      backgroundColor: string
      data: any[]
      yAxisID: string
      fill: boolean
    }
  ]
}

export interface ChartPoints {
  state: string
  points: any[]
  labels: any[]
}
