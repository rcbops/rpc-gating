homeView = Vue.component("homeView",{
  template: `
    <div>
      <titleCard title="All Builds"></titlecard>
      <trendGraphs
        :builds="Object.values(this.$root.builds)"
      ></trendGraphs>
      <failureTables :builds="Object.values(this.$root.builds)">
      </failureTables>
      <repoTable></repoTable>
      <buildTable
        :buildsOrFilter="Object.values(this.$root.builds)"
        title="Recent Builds From All Jobs">
      </buildTable>
    </div>
  `
})

repoDetailView = Vue.component("repoview", {
  props: ["repoName"],
  computed: {
    builds: function(){
      return this.$root.repos.filter(l => l[1]==this.repoName)[0][2]
    }
  },
  template: `
    <div>
      <titleCard
        :title="'Repository: ' + repoName"
      >
        <a :href="this.$root.ghBase+repoName">View this repository on Github</a><v-icon>launch</v-icon>
      </titleCard>
      <trendGraphs
        :builds="this.builds"
      ></trendGraphs>
      <!-- failure types pie -->
      <!-- failure categories pie -->
      <jobTable
        :builds="this.builds"
        title="Jobs"></jobTable>
      <failureTables
        :builds="this.builds">
      </failureTables>
      <buildTable
        title="Build Results"
        :buildsOrFilter="this.builds"
      ></buildTable>
    </div>
  `
})

jobView = Vue.component("jobView", {
  props: ["jobName"],
  computed: {
    builds: function(){
      return Object.values(this.$root.builds).filter(b => b.job_name == this.jobName)
    }
  },
  template: `
    <div>
      <titleCard
        :title="'Job: ' +jobName"
      >
      <a :href="this.$root.jenkinsBase+jobName">View job in in Jenkins</a><v-icon>launch</v-icon>
      </titleCard>
      <trendGraphs
        :builds="this.builds"
      ></trendGraphs>
      <failureTables
        :builds="this.builds"
        :showTopJobs="false">
      </failureTables>
      <buildTable
        title="Build Results"
        :buildsOrFilter="this.builds"
      ></buildTable>
    </div>
  `
})

failureTypeView = Vue.component("failureTypeView",{
  props: {
    type: {},
  },
  data: function(){
    return {
      sort: {
        sortBy: 'numBuilds',
        descending: true
      }
    }
  },
  computed: {
      failures: function(){
        return this.$root.failuresByType(Object.values(this.$root.builds))
          .filter(l=> l[1] == this.type)[0][2]
      },
      builds: function(){
        return this.failures.map(f => f.build)
      },
      category: function(){
        return this.failures[0].category
      }
  },
  methods: {
    dsFilter: function(ds){
      var type = this.type
      return ds.label == type
    }
  },
  template: `
    <div>
      <titleCard
        :title="'Failure Type: '+this.type"
      >
      Failure Category: <router-link :to="'/fcat/'+category">{{this.category}}</router-link>
      </titleCard>
      <failureTypesTrend
        title=""
        :builds="this.builds"
        :dsFilter="this.dsFilter">
      </failureTypesTrend>
      <jobTable
        title="Jobs"
        :builds="this.builds"
        :sort="this.sort"
        :showFailurePercent="false"
        :showTopFailureType="false">
      </jobTable>
      <buildTable
        :buildsOrFilter="this.builds"
        title="Builds"
      ></buildTable>
    </div>
  `
})

failureCategoryView = Vue.component("failureCategoryView",{
  props: {
    category: {},
  },
  data: function(){
    return {
      sort: {
        sortBy: 'numBuilds',
        descending: true
      }
    }
  },
  computed: {
      failures: function(){
        return this.$root.failuresByCategory(Object.values(this.$root.builds))
          .filter(l => l[1] == this.category)[0][2]
      },
      builds: function(){
        return this.failures.map(f => f.build)
      },
  },
  methods: {
    dsFilter: function(ds){
      var category = this.category
      return ds.label == category
    },
    typeFilter: function(failure){
      var category = this.category
      return failure.category == category
    }
  },
  template: `
    <div>
      <titleCard
        :title="'Failure Category: '+this.category"
      >
      </titleCard>
      <failureCategoriesTrend
        title=""
        :builds="this.builds"
        :dsFilter="this.dsFilter">
      </failureCategoriesTrend>
      <jobTable
        title="Jobs"
        :builds="this.builds"
        :sort="this.sort"
        :showFailurePercent="false"
        :showTopFailureCategory="false"
        :typeFilter="this.typeFilter">
      </jobTable>
      <buildTable
        :buildsOrFilter="this.builds"
        title="Builds"
      ></buildTable>
    </div>
  `
})
