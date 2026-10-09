using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace RemoteTool.Lib
{
    public static class Ext
    {
        /// <summary>
        /// 支持 http://user:pass@host:port/xxx 或 http://host:port/xxx
        /// </summary>
        /// <param name="api"></param>
        /// <returns></returns>
        public static (string apiUrl, string username, string password) ParseApiUrl(this string api)
        {
            
            var uri = new Uri(api);
            string user = uri.UserInfo.Contains(":") ? uri.UserInfo.Split(':')[0] : "";
            string pass = uri.UserInfo.Contains(":") ? uri.UserInfo.Split(':')[1] : "";
            string apiUrl = $"{uri.Scheme}://{uri.Host}{(uri.IsDefaultPort ? "" : ":" + uri.Port)}{uri.PathAndQuery}";
            return (apiUrl, user, pass);
        }
    }
}
