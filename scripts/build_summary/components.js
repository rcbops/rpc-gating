titleCard = Vue.component("titleCard",{
  props: {
    "title":{},
    "link": {default: ''}
  },
  template: `
    <v-card>
      <v-card-title primary-title v-if="title.length > 0">
        <h3 v-if="this.link == ''">{{this.title}}</h3>
        <a v-else :href="this.link"><h3>{{this.title}}</h3></a>
      </v-card-title>
      <v-card-text>
        <slot></slot>
      </v-card-text>
    </v-card>
  `
})

trendGraph = Vue.component("trendGraph",{
  props: ["title", "chart"],
  template: `
    <v-card>
      <v-card-title primary-title v-if="title.length > 0">
        <h3>{{this.title}}</h3>
      </v-card-title>
      <v-card-text>
        <canvas height="50px" class="chart"></canvas>
      </v-card-text>
    </v-card>
  `,
  methods: {
    draw: function(){
      var ctx = this.$el.querySelector(".chart").getContext('2d')
      var chart = new Chart(ctx, this.chart)
    }
  },
  mounted: function(){ this.draw() },
})

// durationScatter = Vue.component("durationScatter",{
//     props: {
//       builds: {},
//       title: {}
//     },
//     computed: {
//       chart: function(){
//         return {
//           type: 'scatter',
//           data: {
//             labels: this.$root.labels,
//             datasets: this.datasets,
//           },
//           options: this.$root.stackedOptions
//         }
//       },
//       datasets: function(){
//         var failuresByCategory = this.$root.failuresByCategory(this.builds)
//         var colours = this.$root.colours(failuresByCategory.length + 1)
//         var ds = failuresByCategory.reduce((a,c,i)=>{
//           a.push({
//             label: c[1],
//             // ensure success is always green
//             //backgroundColor: c == "Success" ? colours[0] : colours[(i+1)%(colours.length-1)],
//             backgroundColor: colours[i],
//             data: this.$root.histogram(
//               c[2].map(f => f.build),
//               this.$root.retention_days,
//               1
//             )
//           }); return a
//         },[])
//         return ds.filter(this.dsFilter)
//       },
//     },
//     template: `
//       <trendGraph
//         :title="this.title"
//         :chart="this.chart"
//       ></trendGraph>
//     `
// })

failureTypesTrend = Vue.component("failureTypesTrend",{
  props: {
    "builds":{},
    "title": {},
    "topN": {default: 5},
    "dsFilter": {
      default: function(){
        return function(item){
          return true
        }
      }
    }
  },
  template: `
    <trendGraph
      :title="this.title"
      :chart="this.chart">
    </trendGraph>
  `,
  computed: {
    chart: function(){
      return {
        type: 'bar',
        data: {
          labels: this.$root.labels,
          datasets: this.datasets,
        },
        options: this.$root.stackedOptions
      }
    },
    topFailureTypes: function(){
      return this.$root.failuresByType(this.builds)
        .slice(0,this.topN)
        .map(l => [l[0], l[1], l[2].map(f => f.build)])
      },
    datasets: function(){
      var colours = this.$root.colours(this.topFailureTypes.length + 1)
      var ds = this.topFailureTypes.reduce((a,c,i) => {
        a.push({
          label: c[1],
          // ensure success is always green
          //backgroundColor: c[1] == "Success" ? colours[0] : colours[(i+1)%(colours.length-1)],
          backgroundColor: colours[i],
          data: this.$root.histogram(
            c[2],
            this.$root.retention_days,
            1
          )
        }); return a
      },[])
      var filtered = ds.filter(this.dsFilter)
      return filtered
    },
  }

})
failureCategoriesTrend = Vue.component("failureCategoriesTrend",{
  props: {
    "builds": {},
    "title": {},
    "dsFilter": {
      default: function(){
        return function(item){
          return true
        }
      }
    }
  },
  template: `
    <trendGraph
      :title="this.title"
      :chart="this.chart"></trendGraph>
  `,
  computed: {
    chart: function(){
      return {
        type: 'line',
        data: {
          labels: this.$root.labels,
          datasets: this.datasets,
        },
        options: this.$root.stackedOptions
      }
    },
    datasets: function(){
      var failuresByCategory = this.$root.failuresByCategory(this.builds)
      var colours = this.$root.colours(failuresByCategory.length + 1)
      var ds = failuresByCategory.reduce((a, c, i) => {
        a.push({
          label: c[1],
          // ensure success is always green
          //backgroundColor: c == "Success" ? colours[0] : colours[(i+1)%(colours.length-1)],
          backgroundColor: colours[i],
          data: this.$root.histogram(
            c[2].map(f => f.build),
            this.$root.retention_days,
            1
          )
        }); return a
      },[])
      return ds.filter(this.dsFilter)
    },
  }
})

repoTable = Vue.component("repoTable",{
  data: function(){
    d = {search: ''}
    d.headers=[{text:"Repository", value: "name"},
               {text: "Number of Builds", value:"numBuilds"},
               {text: "Failure %", value: "failPercent"},
               {text: "Most Failing Job", value: "mostFailingJob"},
               {text: "Most Failing Branch", value: "mostFailingBranch"},
               {text: "Top Failure Type", value: "topFailureType"}]
    d.pagination = {
      sortBy: 'failPercent',
      descending: true
    }
    d.rowsperpage = [5, 15, 25, 50, 100, {text:"All",value:-1}]
    d.items = []
    Object.values(this.$root.repos).forEach(r => {
      var builds = r[2]
      var ro = {name: r[1],
                numBuilds: builds.length,
                mostFailingJob: ' ',
                mostFailingBranch: ' ',
                topFailureType: ' ',
                failPercent: 0}
      var buildFailures = builds.filter(b => b.result != "SUCCESS")
      var failCount = buildFailures.length
      ro.failPercent = ((failCount / ro.numBuilds) * 100).toFixed(0)
      mostFailingJob = buildFailures.countBy("job_name")[0]
      ro.mostFailingJob = mostFailingJob[1]
      ro.mostFailingJobCount = mostFailingJob[0]
      mostFailingBranch = buildFailures.countBy("branch")[0]
      ro.mostFailingBranch = mostFailingBranch[1]
      ro.mostFailingBranchCount = mostFailingBranch[0]
      topFailureType = this.$root.failuresByType(builds)[0]
      ro.topFailureType = topFailureType[1]
      ro.topFailureTypeCount = topFailureType[0]
      d.items.push(ro)
    })
    return d
  },
  template: `
    <div>
      <v-card>
        <v-card-title primary-title>
          <h3>Repository Overview</h3>
          <v-spacer></v-spacer>
          <v-text-field
            v-model="search"
            append-icon="search"
            label="Search"
            single-line
            hide-details></v-text-field>
        </v-card-title>
        <v-card-text>
          <v-data-table
            :headers="headers"
            :items="items"
            :pagination.sync=pagination
            :rows-per-page-items="rowsperpage"
            :search="search">
            <template slot="items" slot-scope="props">
              <td><router-link :to="'/repo/'+props.item.name">{{props.item.name}}</router-link></td>
              <td>{{props.item.numBuilds}}</td>
              <td :style="'background-color:'+$root.failColour(props.item.failPercent/100)+';'">{{props.item.failPercent}}</td>
              <td>
                <router-link :to="'/job/'+props.item.mostFailingJob">{{props.item.mostFailingJob}}</router-link>
                ({{props.item.mostFailingJobCount}})
              </td>
              <td>{{props.item.mostFailingBranch}} ({{props.item.mostFailingBranchCount}})</td>
              <td><router-link :to="'/ftype/'+props.item.topFailureType">{{props.item.topFailureType}}</router-link> ({{props.item.topFailureTypeCount}})</td>
            </template>
          </v-data-table>
        </v-card-text>
      </v-card>
    </div>
  `
})
resultTrendGraph = Vue.component("resultTrendGraph", {
  props: ["builds", "title"],
  computed: {
    success: function(){
      return this.$root.histogram(
        this.builds.filter(b => b.result == "SUCCESS"), this.$root.retention_days, 1)
    },
    failurepr: function(){
      return this.$root.histogram(
        this.builds.filter(b => b.result != "SUCCESS" && b.stage == "PR"), this.$root.retention_days, 1)
    },
    failurepm: function(){
      return this.$root.histogram(
        this.builds.filter(b => b.result != "SUCCESS" && b.stage == "PM"), this.$root.retention_days, 1)
    },
    chart: function(){
      return {
        type: 'bar',
        data: {
          labels: this.$root.labels,
          datasets: [
            {
              label: "Periodic Failures",
              backgroundColor: 'rgba(237, 134, 134, 0.39)',
              data: this.failurepm
            },
            {
              label: "PR Failures",
              backgroundColor: 'rgba(237, 186, 134, 0.39)',
              data: this.failurepr
            },
            {
              label: "Success",
              backgroundColor: 'rgba(134, 237, 134, 0.39)',
              data: this.success
            }
          ]
        },
        options: this.$root.stackedOptions,
      }
    }
  },
  template: `
    <trendGraph
      :title="this.title"
      :chart="this.chart">
    </trendGraph>
  `
})

trendGraphs = Vue.component("trendGraphs",{
  props: ["builds"],
  template: `
    <v-tabs>
      <v-tab>Builds by Result</v-tab>
      <v-tab>Failures by Category</v-tab>
      <v-tab>Top 5 Failure Types</v-tab>
      <v-tab-item>
        <resultTrendGraph
          title=""
          :builds="this.builds"></resultTrendGraph>
      </v-tab-item>
      <v-tab-item>
        <failureCategoriesTrend
          title=""
          :builds="this.builds"></failureCategoriesTrend>
      </v-tab-item>
      <v-tab-item>
        <failureTypesTrend
          title=""
          :builds="this.builds">
        </failureTypesTrend>
      </v-tab-item>

    </v-tabs>
  `
})

buildTable = Vue.component("buildTable",{
  props: ['buildsOrFilter', 'title'],
  computed: {
    builds: function(){
      if (typeof this.buildsOrFilter == 'function'){
        // its a filter funciton, use it to filter builds
        return Object.values(this.$root.builds).filter(b => filterfunc(b))
      } else {
        // its already a list of builds, return it
        return this.buildsOrFilter
      }
    },
    items: function(){
      // pull all useful text out of a build object
      // this is used with methods/filter to search
      // as the default search won't look into
      // nested structures
      return this.builds.reduce((a, c)=> {
        c.searchText= [
          c.result,
          c.timestamp.toISOString(),
          c.timestamp.toDateString(),
          c.timestamp.toLocaleDateString(),
          c.branch,
          ...c.build_hierachy.map(b => b.name),
          ...c.build_hierachy.map(b => b.build_num),
          ...c.failures.map(f => f.category),
          ...c.failures.map(f => f.description),
          ...c.failures.map(f => f.detail),
          ...c.failures.map(f => f.type),
        ].join(" ").toLowerCase()
        a.push(c)
        return a
      },[])
    }
  },
  methods: {
    filter: function(items, search, filter){
      return items.filter(i =>
        i.searchText.indexOf(search.toLowerCase()) >= 0)
    }
  },
  data: function(){
      return {
        headers: [
          {text: "Result", value: "result"},
          {text: "Date", value: "timestamp"},
          {text: "Branch", value: "branch"},
          {text: "Job Tree", value: "build_hierachy", sortable: false},
          {text: "Failures", value: "failures", sortable: false}
        ],
        search: '',
        rowsperpage: [5, 15, 25, 50, {text: "All", value: -1}],
        pagination: {
          sortBy: 'timestamp',
          descending: true
        }
      }
  },
  template: `
    <v-card>
      <v-card-title primary-title>
        <h3>{{title}}</h3>
        <v-spacer></v-spacer>
        <v-text-field
          v-model="search"
          append-icon="search"
          label="Search"
          single-line
          hide-details></v-text-field>
      </v-card-title>
      <v-card-text>
        <v-data-table
          :headers="headers"
          :items="items"
          :rows-per-page-items="rowsperpage"
          :search="search"
          :custom-filter="filter"
          :pagination.sync="pagination">
          <template slot="items" slot-scope="props">
            <tr :style="props.item.result == 'SUCCESS' ? '' : 'background-color: rgba(237, 134, 134, 0.39);'">
              <td>
                <p>
                {{props.item.result}}</p>
              </td>
              <td>
                <dateCell :date="props.item.timestamp"></dateCell>
              </td>
              <td>
                {{props.item.branch}}
              </td>
              <td>
                <!-- job tree
                  Suppress link when url is # (usually for cron trigger)
                  special case jobs so that links within build summary
                  are used for the job name and a link to jenkins is
                  used for the build number.
                -->
                <ul>
                  <li :key="parent.url" v-for="parent in props.item.build_hierachy">
                    <span v-if="parent.url != '#'">
                      <router-link
                        v-if="parent.url.includes('/job/')"
                        :to="'/job/'+parent.name">
                          {{parent.name}}
                      </router-link>
                      <a v-else :href="parent.url">{{parent.name}}</a>
                      <a :href="parent.url">{{parent.build_num}}</a>
                    </span>
                    <span v-else>{{parent.name}} {{parent.build_num}}</span>
                  </li>
                </ul>
              </td>
              <td>
                <!-- failures -->
                <ul>
                  <li :key="failure.id" v-for="failure in props.item.failures">
                    <router-link :to="'/ftype/'+failure.type">{{failure.type}}</router-link>
                    {{failure.detail}}
                    <router-link :to="'/fcat/'+failure.category">{{failure.category}}</router-link>
                  </li>
                </ul>
              </td>
            </tr>
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>
  `
})



jobTable = Vue.component("jobTable",{
  props: {
    'builds':{},
    'title':{},
    'sort': {
      default: function(){
        return {
          sortBy: 'failPercent',
          descending: true
        }
      }
    },
    'showFailurePercent':{
      default: true
    },
    'showTopFailureType':{
      default: true
    },
    'typeFilter': {
      default: function(){
        return function(item){
          return true
        }
      }
    }
  },
  computed: {
    // builds: function(){
    //   return this.$root.repos[this.repo.name].jobs
    // },
    items: function(){
      return this.builds.countBy("job_name").reduce((a, c) => {
      //return Object.values(this.jobs).reduce((a,c) => {
        var builds = c[2]
        var name = c[1]
        var numBuilds = builds.length
        var numFailedBuilds = builds.filter(b => b.result != "SUCCESS").length
        var oldestNewest = this.$root.oldestNewestBuilds(builds)
        // typeFilter is used to ensure top failures are only
        // from one category, when viewing a category
        var topFailureTypes = this.$root.failuresByType(builds)
          .filter((t) => this.typeFilter(t[2][0]))

        a.push({
          jobName: name,
          numBuilds: numBuilds,
          oldest: this.$root.dfmt(oldestNewest.oldest.timestamp),
          newest: this.$root.dfmt(oldestNewest.newest.timestamp),
          failPercent: ((numFailedBuilds / numBuilds) * 100).toFixed(0),
          topFailureType: topFailureTypes[0][1]
        })
        return a
      }, [])
    }
  },
  data: function(){
      var d =  {
        headers: [
          {text: "Job", value: "jobName"},
          {text: "Number of Builds", value: "numBuilds"},
          {text: "Oldest Build", value: "oldest"},
          {text: "Newest Build", value: "newest"}
        ],
        search: '',
        rowsperpage: [5, 10, 25, 50, {text: "All", value: -1}],
        pagination: this.sort,
      }
    if(this.showFailurePercent){
      d.headers.push({text: "Failure %", value: "failPercent"})
    }
    if(this.showTopFailureType){
      d.headers.push({text: "Top Failure Type", value: "topFailureType"})
    }
    return d
  },
  template: `
    <v-card>
      <v-card-title primary-title>
        <h3>{{title}}</h3>
        <v-spacer></v-spacer>
        <v-text-field
          v-model="search"
          append-icon="search"
          label="Search"
          single-line
          hide-details></v-text-field>
      </v-card-title>
      <v-card-text>
        <v-data-table
          :headers="headers"
          :items="items"
          :rows-per-page-items="rowsperpage"
          :search="search"
          :pagination.sync="pagination">
          <template slot="items" slot-scope="props">
              <td>
                <router-link :to="'/job/'+props.item.jobName">{{props.item.jobName}}</router-link>
              </td>
              <td>
                {{props.item.numBuilds}}
              </td>
              <td>
                <dateCell :date="props.item.oldest"></dateCell>
              </td>
              <td>
                <dateCell :date="props.item.newest"></dateCell>
              </td>
              <td v-if="showFailurePercent" :style="'background-color:'+$root.failColour(props.item.failPercent/100)+';'">
                {{props.item.failPercent}}
              </td>
              <td v-if="showTopFailureType">
                <router-link :to="'/ftype/'+props.item.topFailureType">
                  {{props.item.topFailureType}}
                </router-link>
              </td>
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>
  `
})

failureTables = Vue.component("failureTables",{
  props: {
    "builds": {},
    "showTopJobs": {
        default: true
    }
  },
  template: `
    <v-tabs>
      <v-tab>Failures by Type</v-tab>
      <v-tab>Failures by Category</v-tab>
      <v-tab-item>
        <failureTable
          :builds="this.builds"
          title=""
          :showTopJobs="this.showTopJobs">
        </failureTable>
      </v-tab-item>
      <v-tab-item>
        <failureCategoryTable
          :builds="this.builds"
          title=""
          :showTopJobs="this.showTopJobs">
        </failureCategoryTable>
      </v-tab-item>
    </v-tabs>
  `
})

failureCategoryTable = Vue.component("failureCategoryTable", {
  props: {
    builds: {},
    title: {},
    numTopJobs: {
      default: 3
    },
    showTopJobs: {
      default: true
    },
    numTopTypes: {
      default: 3
    }
  },
  computed: {
    items: function(){
      // count all the builds passed in, used for % calculation
      var totalBuilds = Object.values(this.builds).length
      // builds by failure type
      var failuresByCategory = this.$root.failuresByCategory(Object.values(this.builds))
      return failuresByCategory.reduce((a, c) => {
        // list of builds for this type
        var category = c[1]
        var categoryBuilds = c[2].map(f => f.build)
        var categoryFailures = c[2]
        var oldestNewest = this.$root.oldestNewestBuilds(categoryBuilds)
        a.push({
          failureCategory: category,
          // in order to find out which category this failure is associated with
          // first get all the failure objects, then find one of the same
          // Category, then read its category.
          failureCategory: categoryFailures[0].category,
          failureOccurences: categoryBuilds.length,
          failurePercent: ((categoryBuilds.length / totalBuilds) * 100).toFixed(0),
          oldest: oldestNewest.oldest.timestamp,
          newest: oldestNewest.newest.timestamp,
          topJobs: categoryBuilds.countBy("job_name").slice(0, this.numTopJobs),
          topFailureTypes: this.$root.failuresByType(categoryBuilds).slice(0, this.numTopTypes)
        })
        return a
      }, [])
    }
  },
  data: function(){
      var d = {
        headers: [
          {text: "Category", value: "failureCategory"},
          {text: "Occurences", value: "failureOccurences"},
          {text: "Percentage of Builds", value: "failurePercent"},
          {text: "Oldest Occurence", value: "oldest"},
          {text: "Newest Occurence", value: "newest"},
          {text: "Top Failure Types", value: "topFailureTypes"},
        ],
        search: '',
        rowsperpage: [5, 10, 25, 50, {text: "All", value: -1}],
        pagination: {
          sortBy: 'failureOccurences',
          descending: true
        },
      }
      if (this.showTopJobs){
        d.headers.push({text: "Top Jobs", value: "topJobs"})
      }
      return d
  },
  template: `
    <v-card>
      <v-card-title primary-title>
        <h3>{{title}}</h3>
        <v-spacer></v-spacer>
        <v-text-field
          v-model="search"
          append-icon="search"
          label="Search"
          single-line
          hide-details></v-text-field>
      </v-card-title>
      <v-card-text>
        <v-data-table
          :headers="headers"
          :items="items"
          :rows-per-page-items="rowsperpage"
          :search="search"
          :pagination.sync="pagination">
          <template slot="items" slot-scope="props">
              <td>
                  <router-link :to="'/fcat/'+props.item.failureCategory">{{props.item.failureCategory}}</router-link>
              </td>
              <td>
                {{props.item.failureOccurences}}
              </td>
              <td :style="'background-color:'+$root.failColour(props.item.failurePercent/100)+';'">
                {{props.item.failurePercent}}
              </td>
              <td>
                <dateCell :date="props.item.oldest"></dateCell>
              </td>
              <td>
                <dateCell :date="props.item.newest"></dateCell>
              </td>
              <td>
                <ul>
                  <li v-for="type in props.item.topFailureTypes" :key="type[1]">
                    <router-link :to="'/ftype/'+type[1]">{{type[1]}}</router-link> ({{type[0]}})
                  </li>
                </ul>
              </td>
              <td v-if="showTopJobs">
                <ul>
                  <li v-for="job in props.item.topJobs" :key="job[1]">
                    <router-link :to="'/job/'+job[1]">{{job[1]}}</router-link> ({{job[0]}})
                  </li>
                </ul>
              </td>
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>
  `
})
failureTable = Vue.component("failureTable",{
  props: {
    'builds':{},
    'title':{},
    'numTopJobs': {
      default: 3
    },
    'showTopJobs': {
      default: true
    },
    'showCategory': {
      default: false
    }
  },
  computed: {
    items: function(){
      // count all the builds passed in, used for % calculation
      var totalBuilds = Object.values(this.builds).length
      // builds by failure type
      var failuresByType = this.$root.failuresByType(Object.values(this.builds))
      return failuresByType.reduce((a, c) => {
        // list of builds for this type
        var type = c[1]
        var typeBuilds = c[2].map(f => f.build)
        var typeFailures = c[2]
        var oldestNewest = this.$root.oldestNewestBuilds(typeBuilds)
        a.push({
          failureType: type,
          // in order to find out which category this failure is associated with
          // first get all the failure objects, then find one of the same
          // type, then read its category.
          failureCategory: typeFailures[0].category,
          failureOccurences: typeBuilds.length,
          failurePercent: ((typeBuilds.length / totalBuilds) * 100).toFixed(0),
          oldest: oldestNewest.oldest.timestamp,
          newest: oldestNewest.newest.timestamp,
          topJobs: typeBuilds.countBy("job_name").slice(0, this.numTopJobs),
        })
        return a
      }, [])
    }
  },
  data: function(){
      var d = {
        headers: [
          {text: "Type", value: "failureType"},
          {text: "Occurences", value: "failureOccurences"},
          {text: "Percentage of Builds", value: "failurePercent"},
          {text: "Oldest Occurence", value: "oldest"},
          {text: "Newest Occurence", value: "newest"},
        ],
        search: '',
        rowsperpage: [5, 10, 25, 50, {text: "All", value: -1}],
        pagination: {
          sortBy: 'failureOccurences',
          descending: true
        },
      }
      if (this.showTopJobs){
        d.headers.push({text: "Top Jobs", value: "topJobs"})
      }
      if(this.showCategory){
        // insert category header at index 1
        d.headers = [d.headers[0],
                     {text: "Category", value: "failureCategory"},
                     ...d.headers.slice(1)]
      }
      return d
  },
  template: `
    <v-card>
      <v-card-title primary-title>
        <h3>{{title}}</h3>
        <v-spacer></v-spacer>
        <v-text-field
          v-model="search"
          append-icon="search"
          label="Search"
          single-line
          hide-details></v-text-field>
      </v-card-title>
      <v-card-text>
        <v-data-table
          :headers="headers"
          :items="items"
          :rows-per-page-items="rowsperpage"
          :search="search"
          :pagination.sync="pagination">
          <template slot="items" slot-scope="props">
              <td>
                <v-tooltip bottom>
                  <router-link slot="activator" :to="'/ftype/'+props.item.failureType">{{props.item.failureType}}</router-link>
                  <span>Category: {{props.item.failureCategory}}</span>
                </v-tooltip>
              </td>
              <td v-if="showCategory">
                <router-link :to="'/fcat/'+props.item.failureCategory">{{props.item.failureCategory}}</router-link>
              </td>
              <td>
                {{props.item.failureOccurences}}
              </td>
              <td :style="'background-color:'+$root.failColour(props.item.failurePercent/100)+';'">
                {{props.item.failurePercent}}
              </td>
              <td>
                <dateCell :date="props.item.oldest"></dateCell>
              </td>
              <td>
                <dateCell :date="props.item.newest"></dateCell>
              </td>
              <td v-if="showTopJobs">
                <ul>
                  <li v-for="job in props.item.topJobs" :key="job[1]">
                    <router-link :to="'/job/'+job[1]">{{job[1]}}</router-link> ({{job[0]}})
                  </li>
                </ul>
              </td>
          </template>
        </v-data-table>
      </v-card-text>
    </v-card>
  `
})

dateCell = Vue.component("dateCell",{
  props: ["date"],
  computed: {
    _date: function(){
      var d = new Date(this.date)
      return d
    }
  },
  template: `
    <v-tooltip bottom>
      <span slot="activator">{{$root.dfmt(date)}}</span>
      <span>{{_date.toRelativeString()}} 🕰 {{_date.toLocaleDateString()}} {{_date.toLocaleTimeString()}} </span>
    </v-tooltip>
  `
})

toolbarmenu = Vue.component("toolbarmenu",{
  props: {
    title: {},
    counteditems: {}, // takes result from builds.countBy()
    urlbase: {},
  },
  computed: {
    items: function(){
      return this.counteditems.reduce((a, c) => {
        a.push({
          title: c[1],
          url: '/' + this.urlbase + '/' + c[1]
        })
        return a
      },[])
    }
  },
  template: `
    <v-menu v-if="this.$root.dataloaded">
      <v-btn flat slot="activator">{{title}}</v-btn>
      <v-list>
        <v-list-tile v-for="item in items" :key="item.url">
          <v-list-tile-title>
            <router-link :to="item.url">{{item.title}}</router-link>
          </v-list-tile-title>
        </v-list-tile>
      </v-list>
    </v-menu>
  `
})
